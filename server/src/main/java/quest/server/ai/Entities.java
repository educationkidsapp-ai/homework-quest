package quest.server.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/** V45 (feature 24): what every AI call cost, in numbers. */
public final class Entities {
    private Entities() {}

    /**
     * One row per <em>model attempt</em> — a rejected answer and a busy provider are attempts too, and both are
     * billed or at least waited for. {@code feature} is the feature that asked, {@code tag} what
     * {@link quest.server.analysis.LlmFailures} files its rejects under, and {@code outcome} one of
     * {@link AiCallLedger#OK}, {@link AiCallLedger#REJECTED}, {@link AiCallLedger#TRANSIENT},
     * {@link AiCallLedger#PROVIDER} or {@link AiCallLedger#ABORTED}.
     *
     * <p><strong>Numbers only.</strong> No prompt, no answer and no name is ever written here: the ledger is for the
     * bill and for the shape of the failures, and a prompt is the one thing that could carry a child's data into a
     * table nobody thinks of as holding any.
     *
     * <p>Tenant table: the `school` filter scopes every read, and `schoolId` is the asking feature's own and never a
     * request parameter. It is nullable, and null is honest rather than convenient — the platform ADMIN's
     * cross-school call and a lesson that has no school yet both really have no school, and a filtered read of
     * another school's rows must not find them either way. There is no foreign key: this row must survive, and a
     * `REQUIRES_NEW` write that must never fail may not depend on another table's row.
     */
    @Entity(name = "AiCallEntity") @Table(name = "ai_calls")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class AiCallEntity {
        @Id private String id;
        @Column(name = "school_id") private String schoolId;
        @Column(nullable = false) private String feature;
        @Column private String tag;
        @Column(nullable = false) private String provider;
        @Column(name = "input_tokens", nullable = false) private long inputTokens;
        @Column(name = "output_tokens", nullable = false) private long outputTokens;
        @Column(nullable = false) private String outcome;
        @Column(name = "duration_ms", nullable = false) private long durationMs;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getFeature() { return feature; } public void setFeature(String v) { feature = v; }
        public String getTag() { return tag; } public void setTag(String v) { tag = v; }
        public String getProvider() { return provider; } public void setProvider(String v) { provider = v; }
        public long getInputTokens() { return inputTokens; } public void setInputTokens(long v) { inputTokens = v; }
        public long getOutputTokens() { return outputTokens; } public void setOutputTokens(long v) { outputTokens = v; }
        public String getOutcome() { return outcome; } public void setOutcome(String v) { outcome = v; }
        public long getDurationMs() { return durationMs; } public void setDurationMs(long v) { durationMs = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
