package quest.server.push;

import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import quest.api.dto.DevicePlatform;
import quest.api.dto.PushMessage;

/**
 * Feature 13: {@link ParentPush} for the other side of the conversation. Until now a message to a member of staff
 * wrote the bell row and stopped there, which is fine on a dashboard that is open and useless on a phone in a pocket.
 *
 * <p>Everything that matters is {@link ParentPush}'s, for the same reasons and through the same
 * {@link PushDispatch}: the pushes leave after the transaction commits, on the bounded pool, in the recipient's own
 * language, and a failure is logged and dropped rather than failing the message. Only two things differ — the phones
 * come from `staff_devices` (V36) and are keyed by `userId`, and the caller decides when: `NotificationService`
 * pushes a staff recipient exactly once per unread bell row, the rule a parent's chat push already follows, and only
 * when the school has `staffMobileApp` on.
 */
@Component
public class StaffPush {
    private static final Logger log = LoggerFactory.getLogger(StaffPush.class);

    /** One member of staff's push; `arabic` is null when the server holds only the English text. */
    public record Delivery(String userId, PushMessage english, PushMessage arabic) {}
    /** Everything one fan-out pushes, published as one event so its phones are read in one statement. */
    public record Requested(List<Delivery> deliveries) {}

    private final ApplicationEventPublisher events; private final StaffDeviceRepository devices; private final PushDispatch dispatch;

    public StaffPush(ApplicationEventPublisher events, StaffDeviceRepository devices, PushDispatch dispatch) {
        this.events = events; this.devices = devices; this.dispatch = dispatch;
    }

    public void toUser(String userId, PushMessage english, PushMessage arabic) {
        if (userId != null && english != null) toUsers(List.of(new Delivery(userId, english, arabic)));
    }

    public void toUsers(List<Delivery> deliveries) {
        var real = deliveries.stream().filter(d -> d.userId() != null && d.english() != null).toList();
        if (!real.isEmpty()) events.publishEvent(new Requested(real));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequested(Requested event) {
        try {
            var byUser = event.deliveries().stream().collect(Collectors.groupingBy(Delivery::userId));
            int dropped = 0;
            for (var phone : devices.findByUserIdIn(byUser.keySet()))
                for (var d : byUser.get(phone.getUserId())) {
                    var message = PushDispatch.localised(d.english(), d.arabic(), phone.getLocale());
                    if (!dispatch.send(phone.getToken(), DevicePlatform.valueOf(phone.getPlatform()), message, devices::deleteByTokenValue)) dropped++;
                }
            if (dropped > 0) log.warn("push: queue full ({} waiting) — {} staff push(es) dropped", dispatch.capacity(), dropped);
        } catch (RuntimeException e) { log.warn("push: could not hand staff pushes over: {}", e.toString()); }
    }
}
