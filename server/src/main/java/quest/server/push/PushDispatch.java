package quest.server.push;

import java.util.Locale;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import quest.api.dto.DevicePlatform;
import quest.api.dto.PushMessage;

/**
 * B4's sending core, in one place: the pool a push leaves on, the retries, and the deletion of a token FCM calls dead.
 *
 * <p>It was {@link ParentPush}'s private half until feature 13 gave staff phones of their own ({@link StaffPush}); two
 * copies of a bounded pool with exponential backoff is precisely the kind of thing that drifts, so the two senders now
 * share this one and keep only what differs — whose phones to read, and when.
 *
 * <p><strong>Bounded.</strong> Sends run on {@link PushProperties#threads} platform threads, so a school-wide
 * broadcast is that many FCM calls at a time rather than one virtual thread per recipient; at most
 * {@link PushProperties#queue} sends wait (retries included), and {@link #send} answers {@code false} for what does
 * not fit so the caller can say how much it dropped. A transient FCM failure is <em>rescheduled</em>, never slept on
 * — exponential backoff (1 s, 4 s) with ±50 % jitter, so a quota error does not bring every retry back in the same
 * instant — at most {@link PushProperties#attempts} times; a dead token is handed to the caller's own
 * {@code onDeadToken}, because only the caller knows which table it is in. Nothing here throws: a failure is logged
 * and dropped, so a push can never fail the message, the release or the broadcast it is about.
 *
 * <p>On shutdown the sends already running finish (10 s at most) and the retries still waiting are dropped, with a
 * line that says how many. Tokens and message text never reach a log line.
 */
@Component
public class PushDispatch implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(PushDispatch.class);

    private final PushSender sender; private final PushProperties props;
    private final ScheduledThreadPoolExecutor pool; private final AtomicInteger waiting = new AtomicInteger();

    public PushDispatch(PushSender sender, PushProperties props) {
        this.sender = sender; this.props = props;
        var threads = new AtomicInteger();
        this.pool = new ScheduledThreadPoolExecutor(props.threads(), r -> {
            var t = new Thread(r, "push-" + threads.incrementAndGet()); t.setDaemon(true); return t;
        });
        this.pool.setRemoveOnCancelPolicy(true);
        this.pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /** One push to one phone, queued. False when the queue is full or the pool is shutting down. */
    public boolean send(String token, DevicePlatform platform, PushMessage message, Consumer<String> onDeadToken) {
        return enqueue(new Send(token, platform, message, 1, onDeadToken), 0);
    }

    /** How many sends may wait at once — what a caller's "dropped" warning quotes. */
    public int capacity() { return props.queue(); }

    /** The Arabic text when the device's locale is Arabic and the server has one, English otherwise. */
    public static PushMessage localised(PushMessage english, PushMessage arabic, String locale) {
        boolean ar = locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ar");
        return ar && arabic != null ? arabic : english;
    }

    /** One send to one phone, which attempt it is, and what to do if the token turns out to be dead. */
    private record Send(String token, DevicePlatform platform, PushMessage message, int attempt, Consumer<String> onDeadToken) {}

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
                case DEAD_TOKEN -> { send.onDeadToken().accept(send.token()); log.info("push: a dead device token was removed"); }
                case RETRY -> retry(send);
            }
        } catch (RuntimeException e) { log.warn("push: a send failed: {}", e.toString()); }
    }

    private void retry(Send send) {
        if (send.attempt() >= props.attempts()) { log.warn("push: a device still failing after {} attempts — dropped", send.attempt()); return; }
        if (!enqueue(new Send(send.token(), send.platform(), send.message(), send.attempt() + 1, send.onDeadToken()), backoff(send.attempt())))
            log.warn("push: a retry could not be queued — dropped");
    }

    /** `backoff × 4^(attempt-1)`, ±50 % so that retries of one burst do not all come back at once. */
    long backoff(int attempt) {
        long base = props.backoff() * (1L << (2 * (attempt - 1)));
        return base == 0 ? 0 : (long) (base * ThreadLocalRandom.current().nextDouble(0.5, 1.5));
    }

    /** Graceful shutdown: running sends finish (10 s at most); retries still waiting are dropped, and the log says so. */
    @Override public void destroy() throws InterruptedException {
        int queued = pool.getQueue().size();
        pool.shutdown();
        if (queued > 0) log.info("push: shutting down — {} waiting send(s) dropped", queued);
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) log.warn("push: {} send(s) cut off at shutdown", pool.shutdownNow().size() + pool.getActiveCount());
    }
}
