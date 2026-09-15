package quest.server.mail;

/**
 * Outgoing email. One implementation logs (QA and tests), one talks to Resend (`quest.mail.provider=resend`).
 * Nothing here ever logs or returns the body of a link: invite and reset tokens travel in `text`/`html` only.
 */
public interface Mailer {
    void send(String to, String subject, String text, String html);
}
