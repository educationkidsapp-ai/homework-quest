package quest.server.mail;

import org.springframework.stereotype.Component;
import quest.server.config.QuestProperties;

/**
 * What the platform calls itself in an email subject. The mail code never spells the name out: it asks this bean,
 * which answers with `quest.platform-name` or, when that is unset, the seeded default from the config constant.
 * P2.1 turns it into a `platform_settings` row and only this class changes.
 */
@Component
public class PlatformName {
    private final String value;
    public PlatformName(QuestProperties props) {
        String configured = props.platformName();
        this.value = configured == null || configured.isBlank() ? QuestProperties.SEEDED_PLATFORM_NAME : configured.trim();
    }

    public String get() { return value; }
    @Override public String toString() { return value; }
}
