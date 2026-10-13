package quest.server.push;

import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <strong>after the transaction commits</strong> ({@link TransactionPhase#AFTER_COMMIT}) and are sent on
 * {@link PushDispatch}'s bounded pool — {@code OutgoingMail}'s reasons: a push is never sent for work that rolled
 * back, FCM's latency never holds a transaction or a request, and a failure is logged and dropped, so a push can
 * never fail the message, the release or the broadcast it is about. `fallbackExecution` lets a caller outside a
 * transaction push too.
 *
 * <p><strong>One read per fan-out.</strong> A broadcast to a school's parents is one event and one
 * `findByParentIdIn` on the committing thread, never a read per parent; a fan-out that reaches nobody with a phone
 * costs that read and no thread at all.
 *
 * <p>Every phone gets one send, in its own language: the Arabic text when the device's locale is Arabic and the
 * server has one, English otherwise. The pool, the retries and the deletion of a dead token are
 * {@link PushDispatch}'s, which feature 13's {@link StaffPush} shares.
 */
@Component
public class ParentPush {
    private static final Logger log = LoggerFactory.getLogger(ParentPush.class);

    /** One parent's push; `arabic` is null when the server holds only the English text. */
    public record Delivery(String parentId, PushMessage english, PushMessage arabic) {}
    /** Everything one fan-out pushes, published as one event so its phones are read in one statement. */
    public record Requested(List<Delivery> deliveries) {}

    private final ApplicationEventPublisher events; private final ParentDeviceRepository devices; private final PushDispatch dispatch;

    public ParentPush(ApplicationEventPublisher events, ParentDeviceRepository devices, PushDispatch dispatch) {
        this.events = events; this.devices = devices; this.dispatch = dispatch;
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
                for (var d : byParent.get(phone.getParentId())) {
                    var message = PushDispatch.localised(d.english(), d.arabic(), phone.getLocale());
                    if (!dispatch.send(phone.getToken(), DevicePlatform.valueOf(phone.getPlatform()), message, devices::deleteByTokenValue)) dropped++;
                }
            if (dropped > 0) log.warn("push: queue full ({} waiting) — {} push(es) dropped", dispatch.capacity(), dropped);
        } catch (RuntimeException e) { log.warn("push: could not hand pushes over: {}", e.toString()); }
    }

    /** Which sender this deployment has: FCM when `quest.push.enabled`, the recorder otherwise. */
    @Configuration
    static class Senders {
        @Bean PushSender pushSender(PushProperties props) { return props.enabled() ? new FcmPushSender() : new RecordingPushSender(); }
    }
}
