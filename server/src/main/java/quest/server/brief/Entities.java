package quest.server.brief;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/** V47 (feature 25): one leader's morning brief for one school day. */
public final class Entities {
    private Entities() {}

    /**
     * The brief as it was written: the numbers it was written from ({@code factsJson}), the lines in both languages
     * (one per line, `\n`-separated, the same things in the same order), and whether the model phrased them
     * ({@code source} = {@code ai}) or the deterministic template did ({@code template}).
     *
     * <p><strong>The unique key `(school_id, user_id, brief_day)` is the claim.</strong>
     * {@link MorningBriefRepository#claim} inserts against it with ON CONFLICT DO NOTHING, as feature 16's
     * `staff_attendance_alerts` does, and only the run whose insert wrote the row calls the model — so two instances
     * waking at 07:15 cannot both generate, and neither can a reloaded Home.
     *
     * <p>Numbers and labels only. No child, parent or employee is named in this table, and none is named to the
     * provider either: the model is handed {@code factsJson}'s counts and nothing else.
     *
     * <p>Tenant table: the `school` filter scopes every read, and `schoolId` is the caller's own, never a request
     * parameter. {@code brief_day} is not `day`, which is a keyword in H2.
     */
    @Entity(name = "MorningBriefEntity") @Table(name = "morning_briefs")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class MorningBriefEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "user_id", nullable = false) private String userId;
        @Column(name = "brief_day", nullable = false) private LocalDate day;
        @Column(name = "facts_json", nullable = false) private String factsJson;
        @Column(name = "text_ar", nullable = false) private String textAr;
        @Column(name = "text_en", nullable = false) private String textEn;
        @Column(nullable = false) private String source;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getUserId() { return userId; } public void setUserId(String v) { userId = v; }
        public LocalDate getDay() { return day; } public void setDay(LocalDate v) { day = v; }
        public String getFactsJson() { return factsJson; } public void setFactsJson(String v) { factsJson = v; }
        public String getTextAr() { return textAr; } public void setTextAr(String v) { textAr = v; }
        public String getTextEn() { return textEn; } public void setTextEn(String v) { textEn = v; }
        public String getSource() { return source; } public void setSource(String v) { source = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
