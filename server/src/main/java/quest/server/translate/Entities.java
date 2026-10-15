package quest.server.translate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/** V51 (feature 29): the translation cache. Nothing here is a record of the school's — the records are the sources. */
public final class Entities {
    private Entities() {}

    /**
     * One translation of one source text into one language.
     *
     * <p><strong>The source is never touched.</strong> That is the owner's rule for this feature and the reason this
     * table exists at all: the message, the broadcast and the announcement stay exactly as they were written, and
     * what a reader asked to be translated lives here beside them. Nothing reads this table to render a message; a
     * client asks for a translation and is told what the cache holds or what the model just said.
     *
     * <p>{@code sourceHash} is the SHA-256 of the exact text that was sent to the model, and it is in the unique
     * index with {@code sourceKind}, {@code sourceId} and {@code lang}. So an <em>edited</em> source translates
     * again — a new hash is a new row — and a repeat request for an unedited one costs nothing, which is the whole
     * point of the cache. The old row stays beside the new one and is simply never found again.
     *
     * <p><strong>No foreign key</strong>, because {@code sourceId} names a row in one of four different tables
     * ({@code chat_messages} twice over, {@code broadcasts}, {@code announcements}) and a single column cannot
     * reference all four. A source that is deleted leaves its translation behind, unreachable: nothing can be read
     * through this table that could not be read through the source.
     *
     * <p>Tenant table: the `school` filter scopes every dashboard read, and {@code schoolId} is the <em>source
     * row's</em> own school — resolved through the filter for a member of staff and through the child for a parent,
     * who carries no tenant scope — and never a request parameter.
     */
    @Entity(name = "TranslationEntity") @Table(name = "translations")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class TranslationEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "source_kind", nullable = false, length = 16) private String sourceKind;
        @Column(name = "source_id", nullable = false) private String sourceId;
        @Column(name = "source_hash", nullable = false, length = 64) private String sourceHash;
        @Column(nullable = false, length = 8) private String lang;
        @Column(nullable = false) private String text;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getSourceKind() { return sourceKind; } public void setSourceKind(String v) { sourceKind = v; }
        public String getSourceId() { return sourceId; } public void setSourceId(String v) { sourceId = v; }
        public String getSourceHash() { return sourceHash; } public void setSourceHash(String v) { sourceHash = v; }
        public String getLang() { return lang; } public void setLang(String v) { lang = v; }
        public String getText() { return text; } public void setText(String v) { text = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
