package quest.server.documents;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/** V48 (feature 26): what a circular said, and nothing of the circular itself. */
public final class Entities {
    private Entities() {}

    /**
     * One document read. {@code fileName} and {@code pages} are what was uploaded and {@code summaryJson} is the
     * validated answer — title, both summaries, the key dates, the actions and the suggested announcement — stored
     * whole, because nothing queries inside it and its shape is the contract's ({@code quest.api.dto.DocumentSummary}).
     *
     * <p><strong>There is no storage path, because there is nothing stored.</strong> The upload is read from a
     * temporary file {@link DocumentReaderService} deletes in a `finally` and is never written to the bucket, so this
     * row is the whole of what the feature keeps and the file cannot be downloaded, attached or leaked afterwards. A
     * row is deleted after thirty days ({@link DocumentRetention}).
     *
     * <p>Tenant table: the `school` filter scopes every read, and `authorId` narrows inside it — a summary is the
     * reader's own, so another manager of the same school is a 404 and another school's is the same answer
     * ({@link DocumentSummaryRepository}).
     */
    @Entity(name = "DocumentSummaryEntity") @Table(name = "document_summaries")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class DocumentSummaryEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "author_id", nullable = false) private String authorId;
        @Column(name = "file_name", nullable = false) private String fileName;
        @Column(nullable = false) private int pages;
        @Column(name = "summary_json", nullable = false) private String summaryJson;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getAuthorId() { return authorId; } public void setAuthorId(String v) { authorId = v; }
        public String getFileName() { return fileName; } public void setFileName(String v) { fileName = v; }
        public int getPages() { return pages; } public void setPages(int v) { pages = v; }
        public String getSummaryJson() { return summaryJson; } public void setSummaryJson(String v) { summaryJson = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
