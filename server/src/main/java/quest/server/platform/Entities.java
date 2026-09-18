package quest.server.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** §A: the platform's own name, logo and support address. One row, {@link #ID}. */
public final class Entities {
    private Entities() {}

    @Entity(name = "PlatformSettingsEntity") @Table(name = "platform_settings")
    public static class PlatformSettingsEntity {
        /** The single row `V5__flags_themes.sql` seeds; there is one platform. */
        public static final String ID = "default";

        @Id private String id = ID;
        @Column(nullable = false) private String name;
        @Column(name = "short_name", nullable = false) private String shortName;
        @Column(name = "logo_url") private String logoUrl;
        @Column(name = "support_email") private String supportEmail;
        @Column(name = "default_theme_json") private String defaultThemeJson;
        /** V7, N2.1: the teaching days as a JSON list of three-letter `DayOfWeek` names, and the IANA zone. */
        @Column(name = "school_week_json", nullable = false) private String schoolWeekJson = "[]";
        @Column(nullable = false) private String timezone = "UTC";
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getName() { return name; } public void setName(String v) { name = v; }
        public String getShortName() { return shortName; } public void setShortName(String v) { shortName = v; }
        public String getLogoUrl() { return logoUrl; } public void setLogoUrl(String v) { logoUrl = v; }
        public String getSupportEmail() { return supportEmail; } public void setSupportEmail(String v) { supportEmail = v; }
        public String getDefaultThemeJson() { return defaultThemeJson; } public void setDefaultThemeJson(String v) { defaultThemeJson = v; }
        public String getSchoolWeekJson() { return schoolWeekJson; } public void setSchoolWeekJson(String v) { schoolWeekJson = v; }
        public String getTimezone() { return timezone; } public void setTimezone(String v) { timezone = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
    }
}
