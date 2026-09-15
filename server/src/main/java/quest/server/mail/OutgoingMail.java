package quest.server.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The queue every dashboard mail goes through. A caller publishes what it wants sent and returns; the mail leaves
 * after the transaction commits ({@link TransactionPhase#AFTER_COMMIT}) and on a task thread ({@code @Async}), which
 * buys three things:
 *
 * <ul>
 *   <li>the response time no longer depends on the mailer, so `POST /auth/forgot-password` answers 204 in the same
 *       time for an address that has an account and one that does not (no enumeration by stopwatch);</li>
 *   <li>a slow provider (ResendMailer allows itself 20 s) never holds a database transaction open;</li>
 *   <li>a mail is never sent for work that rolled back — a failed invite cannot email a link to an account that
 *       does not exist.</li>
 * </ul>
 *
 * <p>`fallbackExecution = true` keeps the events working outside a transaction (a caller that is not `@Transactional`
 * still gets its mail). A provider failure is logged and dropped: the caller has already been answered, and the
 * person can ask for another link.
 */
@Component
public class OutgoingMail {
    private static final Logger log = LoggerFactory.getLogger(OutgoingMail.class);

    /** The one-time token travels in the event, never in a log line. */
    public record InviteRequested(String to, String schoolName, String role, String token) {}
    public record PasswordResetRequested(String to, String token) {}

    private final ApplicationEventPublisher events; private final DashboardMails mails;
    public OutgoingMail(ApplicationEventPublisher events, DashboardMails mails) { this.events = events; this.mails = mails; }

    public void invite(String to, String schoolName, String role, String token) { events.publishEvent(new InviteRequested(to, schoolName, role, token)); }

    public void passwordReset(String to, String token) { events.publishEvent(new PasswordResetRequested(to, token)); }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInvite(InviteRequested event) {
        send(() -> mails.sendInvite(event.to(), event.schoolName(), event.role(), event.token()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPasswordReset(PasswordResetRequested event) {
        send(() -> mails.sendPasswordReset(event.to(), event.token()));
    }

    private void send(Runnable send) {
        try { send.run(); }
        catch (RuntimeException e) { log.error("a dashboard mail could not be sent: {}", e.toString()); }
    }
}
