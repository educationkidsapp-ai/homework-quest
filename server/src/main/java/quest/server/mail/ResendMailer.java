package quest.server.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Resend (`quest.mail.provider=resend`, `RESEND_API_KEY`, `MAIL_FROM`). A failure is logged, never thrown at the caller. */
public class ResendMailer implements Mailer {
    private static final Logger log = LoggerFactory.getLogger(ResendMailer.class);
    private static final URI ENDPOINT = URI.create("https://api.resend.com/emails");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper; private final String apiKey; private final String from;
    public ResendMailer(ObjectMapper mapper, String apiKey, String from) { this.mapper = mapper; this.apiKey = apiKey; this.from = from; }

    @Override public void send(String to, String subject, String text, String html) {
        try {
            var body = mapper.writeValueAsString(Map.of("from", from, "to", new String[] {to}, "subject", subject, "text", text, "html", html));
            var request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) log.error("resend rejected the mail \"{}\" to {} with {}", subject, LogMailer.redact(to), response.statusCode());
            else log.info("mail \"{}\" -> {} (resend)", subject, LogMailer.redact(to));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); log.error("mail \"{}\" interrupted", subject); }
        catch (Exception e) { log.error("mail \"{}\" to {} failed: {}", subject, LogMailer.redact(to), e.getMessage()); }
    }
}
