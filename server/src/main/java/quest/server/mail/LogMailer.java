package quest.server.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The default mailer: subject + a redacted recipient go to the log, the body (which carries the token) never does. */
public class LogMailer implements Mailer {
    private static final Logger log = LoggerFactory.getLogger(LogMailer.class);

    @Override public void send(String to, String subject, String text, String html) { log.info("mail \"{}\" -> {}", subject, redact(to)); }

    /** `sara.ahmed@school.test` -> `s***d@school.test`; a malformed address keeps nothing but its shape. */
    public static String redact(String address) {
        if (address == null || address.isBlank()) return "(none)";
        int at = address.indexOf('@');
        if (at <= 0) return "***";
        String local = address.substring(0, at);
        String masked = local.length() == 1 ? "*" : local.charAt(0) + "***" + local.charAt(local.length() - 1);
        return masked + address.substring(at);
    }
}
