package quest.server.files;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/** V24 (MH1): uploaded attachment bytes — the weekly plan's image, and whatever a composer attaches to a broadcast. */
public final class Entities {
    private Entities() {}

    /**
     * Tenant table: the `school` filter scopes every dashboard read, and `schoolId` is the uploader's own school,
     * written by {@link AttachmentService} and never taken from a request. A parent runs with no filter at all — she
     * carries no tenant scope — so her side is checked against the school of the broadcast that carries the row
     * ({@link MediaAccess#requireAttachment}).
     *
     * <p>`storagePath` is the {@link FileStore} key rather than a URL: the bytes are in a bucket on QA and in a
     * directory in the tests, and only `/media/attachments/{id}` ever hands them out.
     */
    @Entity(name = "AttachmentEntity") @Table(name = "attachments")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class AttachmentEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "uploaded_by", nullable = false) private String uploadedBy;
        @Column(nullable = false) private String name;
        @Column(name = "mime_type", nullable = false) private String mimeType;
        @Column(name = "size_bytes", nullable = false) private long sizeBytes;
        @Column(name = "storage_path", nullable = false) private String storagePath;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        /** V33 (B5): `broadcast` or `chat`; a chat upload is sent with one message, which {@code messageId} names. */
        @Column(nullable = false) private String purpose = BROADCAST;
        @Column(name = "message_id") private String messageId;
        @Column private Integer width;
        @Column private Integer height;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getUploadedBy() { return uploadedBy; } public void setUploadedBy(String v) { uploadedBy = v; }
        public String getName() { return name; } public void setName(String v) { name = v; }
        public String getMimeType() { return mimeType; } public void setMimeType(String v) { mimeType = v; }
        public long getSizeBytes() { return sizeBytes; } public void setSizeBytes(long v) { sizeBytes = v; }
        public String getStoragePath() { return storagePath; } public void setStoragePath(String v) { storagePath = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public String getPurpose() { return purpose; } public void setPurpose(String v) { purpose = v; }
        public String getMessageId() { return messageId; } public void setMessageId(String v) { messageId = v; }
        public Integer getWidth() { return width; } public void setWidth(Integer v) { width = v; }
        public Integer getHeight() { return height; } public void setHeight(Integer v) { height = v; }
        public boolean isChat() { return CHAT.equals(purpose); }
    }

    /** V33 `attachments.purpose`. */
    public static final String BROADCAST = "broadcast", CHAT = "chat";
}
