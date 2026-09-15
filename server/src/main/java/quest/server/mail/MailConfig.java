package quest.server.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import quest.server.config.QuestProperties;

/** `quest.mail.provider`: `resend` needs an API key and a from-address, anything else logs. Tests override the bean. */
@Configuration
public class MailConfig {
    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean @ConditionalOnMissingBean(Mailer.class)
    public Mailer mailer(QuestProperties props, ObjectMapper mapper) {
        var mail = props.mail();
        boolean resend = mail != null && "resend".equalsIgnoreCase(mail.provider());
        if (resend && (mail.apiKey() == null || mail.apiKey().isBlank())) {
            log.error("MAIL_PROVIDER=resend but RESEND_API_KEY is empty; falling back to the log mailer");
            return new LogMailer();
        }
        if (!resend) { log.info("mail provider: log"); return new LogMailer(); }
        log.info("mail provider: resend, from {}", LogMailer.redact(mail.from()));
        return new ResendMailer(mapper, mail.apiKey(), mail.from());
    }
}
