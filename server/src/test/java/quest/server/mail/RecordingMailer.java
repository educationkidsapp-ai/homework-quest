package quest.server.mail;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The mailer the tests read: same contract as {@link LogMailer}, but it keeps what it was asked to send, together with
 * the thread it was sent on and whether a transaction was still open — which is how {@link OutgoingMail}'s promise
 * (after commit, off the request thread) is asserted. Mail is asynchronous now, so the readers wait for it.
 */
public class RecordingMailer implements Mailer {
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9._\\-]+)");
    private static final Duration WAIT = Duration.ofSeconds(5);

    /** `thread` and `inTransaction` are recorded at send time, not at read time. */
    public record Message(String to, String subject, String text, String html, String thread, boolean inTransaction) {
        /** The one-time token out of the link in the body — what a person would click. */
        public String token() {
            Matcher m = TOKEN.matcher(text);
            if (!m.find()) throw new AssertionError("no token in the mail body");
            return m.group(1);
        }
    }

    private final List<Message> sent = new ArrayList<>();

    @Override public synchronized void send(String to, String subject, String text, String html) {
        sent.add(new Message(to, subject, text, html, Thread.currentThread().getName(), TransactionSynchronizationManager.isActualTransactionActive()));
        notifyAll();
    }

    public synchronized List<Message> sent() { return List.copyOf(sent); }

    /** The last mail, waiting up to five seconds for the asynchronous send to land. */
    public Message last() { return awaitAtLeast(1).getLast(); }

    /** Every mail sent so far, once at least `count` of them have arrived. */
    public synchronized List<Message> awaitAtLeast(int count) {
        Instant deadline = Instant.now().plus(WAIT);
        while (sent.size() < count) {
            long remaining = Duration.between(Instant.now(), deadline).toMillis();
            if (remaining <= 0) throw new AssertionError("waited " + WAIT.toSeconds() + "s for " + count + " mail(s); " + sent.size() + " arrived");
            try { wait(remaining); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
        return List.copyOf(sent);
    }

    /** What has arrived after giving a send that should not happen a fair chance to happen. */
    public List<Message> settled() {
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return sent();
    }

    public synchronized void clear() { sent.clear(); }

    /** `@Import(RecordingMailer.Config.class)` on a test swaps the real mailer out. */
    @TestConfiguration
    public static class Config {
        @Bean @Primary public RecordingMailer recordingMailer() { return new RecordingMailer(); }
    }
}
