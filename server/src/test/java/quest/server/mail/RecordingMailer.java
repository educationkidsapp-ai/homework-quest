package quest.server.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The mailer the tests read: same contract as {@link LogMailer}, but it keeps what it was asked to send. */
public class RecordingMailer implements Mailer {
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9._\\-]+)");

    public record Message(String to, String subject, String text, String html) {
        /** The one-time token out of the link in the body — what a person would click. */
        public String token() {
            Matcher m = TOKEN.matcher(text);
            if (!m.find()) throw new AssertionError("no token in the mail body");
            return m.group(1);
        }
    }

    private final List<Message> sent = new ArrayList<>();

    @Override public synchronized void send(String to, String subject, String text, String html) { sent.add(new Message(to, subject, text, html)); }

    public synchronized List<Message> sent() { return List.copyOf(sent); }
    public synchronized Message last() {
        if (sent.isEmpty()) throw new AssertionError("no mail was sent");
        return sent.get(sent.size() - 1);
    }
    public synchronized void clear() { sent.clear(); }

    /** `@Import(RecordingMailer.Config.class)` on a test swaps the real mailer out. */
    @TestConfiguration
    public static class Config {
        @Bean @Primary public RecordingMailer recordingMailer() { return new RecordingMailer(); }
    }
}
