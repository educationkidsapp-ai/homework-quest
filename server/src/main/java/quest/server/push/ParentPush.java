package quest.server.push;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import quest.api.dto.DevicePlatform;
import quest.api.dto.PushMessage;

/**
 * B4: parents are pushed what was just written for them. A caller hands over one fan-out's pushes and returns; they leave
 * <strong>after the transaction commits</strong> ({@link TransactionPhase#AFTER_COMMIT}) and are sent <strong>on this
 * class's own pool</strong> — {@code OutgoingMail}'s reasons: a push is never sent for work that rolled back, FCM's
 * latency never holds a transaction or a request, and a failure is logged and dropped, so a push can never fail the
 * message, the release or the broadcast it is about. `fallbackExecution` lets a caller outside a transaction push too.
 *
 * <p><strong>One read per fan-out.</strong> A broadcast to a school's parents is one event and one
 * `findByParentIdIn` on the committing thread, never a read per parent; a fan-out that reaches nobody with a phone
 * costs that read and no thread at all.
 *
 * <p><strong>Bounded.</strong> Sends run on {@link PushProperties#threads} platform threads, so a school-wide
 * broadcast is that many FCM calls at a time, not one virtual thread per parent; at most {@link PushProperties#queue}
 * sends wait (retries included), and a fan-out arriving at a full queue drops what does not fit, with a warning that
 * says how many. A transient FCM failure is <em>rescheduled</em>, not slept on — exponential backoff (1 s, 4 s) with ±50 %
 * jitter, so a quota error does not bring every retry back in the same instant — at most {@link PushProperties#attempts}
 * times; a dead token is deleted. On shutdown the sends already running finish (10 s at most) and the retries still
 * waiting are dropped, with a line that says how many.
 *
 * <p>Every phone gets one send, in its own language: the Arabic text when the device's locale is Arabic and the server
 * has one, English otherwise. Tokens and message text never reach a log line.
 */
@Component
public class ParentPush implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(ParentPush.class);

    /** One parent's push; `arabic` is null when the server holds only the English text. */
    public record Delivery(String parentId, PushMessage english, PushMessage arabic) {}
    /** Everything one fan-out pushes, published as one event so its phones are read in one statement. */
    public record Requested(List<Delivery> deliveries) {}

    private final ApplicationEventPublisher events; private final ParentDeviceRepository devices;
    private final PushSender sender; private final PushProperties props;
    private final ScheduledThreadPoolExecutor pool; private final AtomicInteger waiting = new AtomicInteger();

    public ParentPush(ApplicationEventPublisher events, ParentDeviceRepository devices, PushSender sender, PushProperties props) {
        this.events = events; this.devices = devices; this.sender = sender; this.props = props;
        var threads = new AtomicInteger();
        this.pool = new ScheduledThreadPoolExecutor(props.threads(), r -> {
            var t = new Thread(r, "push-" + threads.incrementAndGet()); t.setDaemon(true); return t;
        });
        this.pool.setRemoveOnCancelPolicy(true);
        this.pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public void toParent(String parentId, PushMessage english, PushMessage arabic) {
        if (parentId != null && english != null) toParents(List.of(new Delivery(parentId, english, arabic)));
    }

    public void toParents(List<Delivery> deliveries) {
        var real = deliveries.stream().filter(d -> d.parentId() != null && d.english() != null).toList();
        if (!real.isEmpty()) events.publishEvent(new Requested(real));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequested(Requested event) {
        try {
            // A parent may be told several things by one fan-out — one row per child of hers on a section.
            var byParent = event.deliveries().stream().collect(Collectors.groupingBy(Delivery::parentId));
            int dropped = 0;
            for (var phone : devices.findByParentIdIn(byParent.keySet()))
                for (var d : byParent.get(phone.getParentId()))
                    if (!enqueue(new Send(phone.getToken(), DevicePlatform.valueOf(phone.getPlatform()), localised(d, phone.getLocale()), 1), 0)) dropped++;
            if (dropped > 0) log.warn("push: queue full ({} waiting) — {} push(es) dropped", props.queue(), dropped);
        } catch (RuntimeException e) { log.warn("push: could not hand pushes over: {}", e.toString()); }
    }

    /** One send to one phone, and which attempt it is. */
    private record Send(String token, DevicePlatform platform, PushMessage message, int attempt) {}

    /** Into the pool, unless {@link PushProperties#queue} sends are already waiting or the pool is shutting down. */
    private boolean enqueue(Send send, long delayMillis) {
        if (waiting.incrementAndGet() > props.queue()) { waiting.decrementAndGet(); return false; }
        try { pool.schedule(() -> run(send), delayMillis, TimeUnit.MILLISECONDS); return true; }
        catch (java.util.concurrent.RejectedExecutionException shuttingDown) { waiting.decrementAndGet(); return false; }
    }

    private void run(Send send) {
        waiting.decrementAndGet();
        try {
            switch (sender.send(send.token(), send.platform(), send.message())) {
                case SENT, FAILED -> { }
                case DEAD_TOKEN -> { devices.deleteByTokenValue(send.token()); log.info("push: a dead device token was removed"); }
                case RETRY -> retry(send);
            }
        } catch (RuntimeException e) { log.warn("push: a send failed: {}", e.toString()); }
    }

    private void retry(Send send) {
        if (send.attempt() >= props.attempts()) { log.warn("push: a device still failing after {} attempts — dropped", send.attempt()); return; }
        if (!enqueue(new Send(send.token(), send.platform(), send.message(), send.attempt() + 1), backoff(send.attempt())))
            log.warn("push: a retry could not be queued — dropped");
    }

    /** `backoff × 4^(attempt-1)`, ±50 % so that retries of one burst do not all come back at once. */
    long backoff(int attempt) {
        long base = props.backoff() * (1L << (2 * (attempt - 1)));
        return base == 0 ? 0 : (long) (base * ThreadLocalRandom.current().nextDouble(0.5, 1.5));
    }

    static PushMessage localised(Delivery delivery, String locale) {
        boolean arabic = locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ar");
        return arabic && delivery.arabic() != null ? delivery.arabic() : delivery.english();
    }

    /** Graceful shutdown: running sends finish (10 s at most); retries still waiting are dropped, and the log says so. */
    @Override public void destroy() throws InterruptedException {
        int queued = pool.getQueue().size();
        pool.shutdown();
        if (queued > 0) log.info("push: shutting down — {} waiting send(s) dropped", queued);
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) log.warn("push: {} send(s) cut off at shutdown", pool.shutdownNow().size() + pool.getActiveCount());
    }

    /** Which sender this deployment has: FCM when `quest.push.enabled`, the recorder otherwise. */
    @Configuration
    static class Senders {
        @Bean PushSender pushSender(PushProperties props) { return props.enabled() ? new FcmPushSender() : new RecordingPushSender(); }
    }
}
