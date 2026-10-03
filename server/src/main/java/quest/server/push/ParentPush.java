package quest.server.push;

import java.util.ArrayList;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import quest.api.dto.DevicePlatform;
import quest.api.dto.PushMessage;
import quest.server.push.Entities.ParentDeviceEntity;

/**
 * B4: a parent is pushed what was just written for her. A caller hands over the push and returns; it leaves <strong>after
 * the transaction commits</strong> ({@link TransactionPhase#AFTER_COMMIT}) and <strong>on a task thread</strong>
 * ({@code @Async}) — {@code OutgoingMail}'s reasons: a push is never sent for work that rolled back, FCM's latency never
 * holds a transaction or a request, and a failure is logged and dropped, so a push can never fail the message, the
 * release or the broadcast it is about. `fallbackExecution` lets a caller outside a transaction push too.
 *
 * <p>Every phone of hers gets one send, in its own language: the Arabic text when the device's locale is Arabic and the
 * server has one, English otherwise. A transient FCM failure is retried at most {@link PushProperties#attempts} times
 * with a growing pause; a dead token is deleted. Tokens and message text never reach a log line at INFO.
 */
@Component
public class ParentPush {
    private static final Logger log = LoggerFactory.getLogger(ParentPush.class);

    /** `arabic` is null when the server holds only the English text. */
    public record Requested(String parentId, PushMessage english, PushMessage arabic) {}

    private final ApplicationEventPublisher events; private final ParentDeviceRepository devices;
    private final PushSender sender; private final PushProperties props;

    public ParentPush(ApplicationEventPublisher events, ParentDeviceRepository devices, PushSender sender, PushProperties props) {
        this.events = events; this.devices = devices; this.sender = sender; this.props = props;
    }

    public void toParent(String parentId, PushMessage english, PushMessage arabic) {
        if (parentId != null && english != null) events.publishEvent(new Requested(parentId, english, arabic));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequested(Requested event) {
        try { deliver(event); }
        catch (RuntimeException e) { log.warn("push: delivery to a parent failed: {}", e.toString()); }
    }

    private void deliver(Requested event) {
        var pending = new ArrayList<ParentDeviceEntity>(devices.findByParentIdOrderByLastSeenAtDescIdAsc(event.parentId()));
        for (int attempt = 1; !pending.isEmpty(); attempt++) {
            var again = new ArrayList<ParentDeviceEntity>();
            for (var device : pending) {
                var outcome = sender.send(device.getToken(), DevicePlatform.valueOf(device.getPlatform()), localised(event, device.getLocale()));
                switch (outcome) {
                    case SENT, FAILED -> { }
                    case DEAD_TOKEN -> { devices.deleteByTokenValue(device.getToken()); log.info("push: a dead device token was removed"); }
                    case RETRY -> again.add(device);
                }
            }
            if (again.isEmpty()) return;
            if (attempt >= props.attempts()) { log.warn("push: {} device(s) still failing after {} attempts", again.size(), attempt); return; }
            if (!pause(props.backoff() * (1L << (2 * (attempt - 1))))) return;
            pending = again;
        }
    }

    static PushMessage localised(Requested event, String locale) {
        boolean arabic = locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ar");
        return arabic && event.arabic() != null ? event.arabic() : event.english();
    }

    /** False when the thread was interrupted (the instance is shutting down): the retry is abandoned. */
    private static boolean pause(long millis) {
        if (millis <= 0) return true;
        try { Thread.sleep(millis); return true; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    /** Which sender this deployment has: FCM when `quest.push.enabled`, the recorder otherwise. */
    @Configuration
    static class Senders {
        @Bean PushSender pushSender(PushProperties props) { return props.enabled() ? new FcmPushSender() : new RecordingPushSender(); }
    }
}
