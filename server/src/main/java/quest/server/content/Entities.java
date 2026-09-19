package quest.server.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

public final class Entities {
    private Entities() {}

    @Entity @Table(name = "courses")
    public static class CourseEntity {
        @Id private String id; @Column(nullable = false) private String curriculum; @Column(nullable = false) private int grade;
        public String getId() { return id; } public String getCurriculum() { return curriculum; } public int getGrade() { return grade; }
    }

    /** Tenant table: every read through a scoped request is filtered to the caller's school (`quest.server.tenancy`). */
    @Entity(name = "LessonEntity") @Table(name = "lessons")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class LessonEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId = "default";
        @Column(name = "course_id", nullable = false) private String courseId;
        @Column(name = "class_id") private String classId;
        @Column(nullable = false) private String subject;
        @Column(nullable = false) private LocalDate date;
        @Column(nullable = false) private String status;
        @Column(nullable = false) private int version;
        private String title; private String notes;
        @Column(name = "practice_length", nullable = false) private int practiceLength = 7;
        @Column(name = "source_hash") private String sourceHash;
        @Column(nullable = false) private String source = "pdf";
        @Column(name = "current_step") private String currentStep;
        @Column(name = "token_usage", nullable = false) private long tokenUsage;
        @Column(name = "tokens_saved", nullable = false) private long tokensSaved;
        @Column(name = "error_code") private String errorCode; @Column(name = "error_message") private String errorMessage;
        @Column(name = "created_by") private String createdBy;
        /** Since V7: the teacher who wrote it (§6 screen 12), and `homework` or `exam` (N4.3 fills the second). */
        @Column(name = "teacher_id") private String teacherId;
        @Column(nullable = false) private String type = "homework";
        /** V8: the lesson this one was copied from (§4's drag-to-copy), and whether its analysis came from the cache. */
        @Column(name = "copied_from_lesson_id") private String copiedFromLessonId;
        @Column(name = "analysis_cache_hit", nullable = false) private boolean analysisCacheHit;
        @Column(name = "created_at", nullable = false) private Instant createdAt; @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        @Column(name = "published_at") private Instant publishedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getCourseId() { return courseId; } public void setCourseId(String v) { courseId = v; }
        public String getClassId() { return classId; } public void setClassId(String v) { classId = v; }
        public String getSubject() { return subject; } public void setSubject(String v) { subject = v; }
        public LocalDate getDate() { return date; } public void setDate(LocalDate v) { date = v; }
        public String getStatus() { return status; } public void setStatus(String v) { status = v; }
        public int getVersion() { return version; } public void setVersion(int v) { version = v; }
        public String getTitle() { return title; } public void setTitle(String v) { title = v; }
        public String getNotes() { return notes; } public void setNotes(String v) { notes = v; }
        public int getPracticeLength() { return practiceLength; } public void setPracticeLength(int v) { practiceLength = v; }
        public String getSourceHash() { return sourceHash; } public void setSourceHash(String v) { sourceHash = v; }
        public String getSource() { return source; } public void setSource(String v) { source = v; }
        public String getCurrentStep() { return currentStep; } public void setCurrentStep(String v) { currentStep = v; }
        public long getTokenUsage() { return tokenUsage; } public void setTokenUsage(long v) { tokenUsage = v; }
        public long getTokensSaved() { return tokensSaved; } public void setTokensSaved(long v) { tokensSaved = v; }
        public String getErrorCode() { return errorCode; } public void setErrorCode(String v) { errorCode = v; }
        public String getErrorMessage() { return errorMessage; } public void setErrorMessage(String v) { errorMessage = v; }
        public String getCreatedBy() { return createdBy; } public void setCreatedBy(String v) { createdBy = v; }
        public String getTeacherId() { return teacherId; } public void setTeacherId(String v) { teacherId = v; }
        public String getType() { return type; } public void setType(String v) { type = v; }
        public String getCopiedFromLessonId() { return copiedFromLessonId; } public void setCopiedFromLessonId(String v) { copiedFromLessonId = v; }
        public boolean isAnalysisCacheHit() { return analysisCacheHit; } public void setAnalysisCacheHit(boolean v) { analysisCacheHit = v; }
        /** The lesson every copy of this one hangs off: itself, unless it is already a copy. */
        public String lineageRoot() { return copiedFromLessonId == null ? id : copiedFromLessonId; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
        public Instant getPublishedAt() { return publishedAt; } public void setPublishedAt(Instant v) { publishedAt = v; }
    }

    @Entity @Table(name = "source_files")
    public static class SourceFileEntity {
        @Id private String id;
        @Column(name = "lesson_id", nullable = false) private String lessonId;
        @Column(name = "file_name", nullable = false) private String fileName;
        @Column(name = "file_hash", nullable = false) private String fileHash;
        @Column(nullable = false) private String kind;
        @Column(name = "mime_type", nullable = false) private String mimeType;
        @Column(name = "page_count", nullable = false) private int pageCount;
        @Column(name = "storage_path", nullable = false) private String storagePath;
        @Column(name = "size_bytes", nullable = false) private long sizeBytes;
        @Column(name = "cache_hit", nullable = false) private boolean cacheHit;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "deleted_at") private Instant deletedAt;
        /** CR4 (V10): the Markdown the model reads instead of this file. See {@code ConversionService}. */
        @Column(name = "markdown_path") private String markdownPath;
        @Column(name = "convert_status", nullable = false) private String convertStatus = "pending";
        @Column(name = "convert_error_code") private String convertErrorCode;
        @Column(name = "convert_method") private String convertMethod;
        @Column(name = "markdown_chars") private Integer markdownChars;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public String getFileName() { return fileName; } public void setFileName(String v) { fileName = v; }
        public String getFileHash() { return fileHash; } public void setFileHash(String v) { fileHash = v; }
        public String getKind() { return kind; } public void setKind(String v) { kind = v; }
        public String getMimeType() { return mimeType; } public void setMimeType(String v) { mimeType = v; }
        public int getPageCount() { return pageCount; } public void setPageCount(int v) { pageCount = v; }
        public String getStoragePath() { return storagePath; } public void setStoragePath(String v) { storagePath = v; }
        public long getSizeBytes() { return sizeBytes; } public void setSizeBytes(long v) { sizeBytes = v; }
        public boolean isCacheHit() { return cacheHit; } public void setCacheHit(boolean v) { cacheHit = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public Instant getDeletedAt() { return deletedAt; } public void setDeletedAt(Instant v) { deletedAt = v; }
        public String getMarkdownPath() { return markdownPath; } public void setMarkdownPath(String v) { markdownPath = v; }
        public String getConvertStatus() { return convertStatus; } public void setConvertStatus(String v) { convertStatus = v; }
        public String getConvertErrorCode() { return convertErrorCode; } public void setConvertErrorCode(String v) { convertErrorCode = v; }
        public String getConvertMethod() { return convertMethod; } public void setConvertMethod(String v) { convertMethod = v; }
        public Integer getMarkdownChars() { return markdownChars; } public void setMarkdownChars(Integer v) { markdownChars = v; }
    }

    @Entity @Table(name = "skills")
    public static class SkillEntity {
        @Id private String id;
        @Column(name = "lesson_id", nullable = false) private String lessonId;
        @Column(nullable = false) private String name; @Column(nullable = false) private String subject; @Column(nullable = false) private String method;
        @Column(name = "examples_json", nullable = false) private String examplesJson = "[]";
        @Column(name = "slide_numbers_json", nullable = false) private String slideNumbersJson = "[]";
        @Column(nullable = false) private double confidence;
        @Column(name = "unsure_json") private String unsureJson;
        @Column(nullable = false) private boolean confirmed;
        @Column(nullable = false) private int position;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public String getName() { return name; } public void setName(String v) { name = v; }
        public String getSubject() { return subject; } public void setSubject(String v) { subject = v; }
        public String getMethod() { return method; } public void setMethod(String v) { method = v; }
        public String getExamplesJson() { return examplesJson; } public void setExamplesJson(String v) { examplesJson = v; }
        public String getSlideNumbersJson() { return slideNumbersJson; } public void setSlideNumbersJson(String v) { slideNumbersJson = v; }
        public double getConfidence() { return confidence; } public void setConfidence(double v) { confidence = v; }
        public String getUnsureJson() { return unsureJson; } public void setUnsureJson(String v) { unsureJson = v; }
        public boolean isConfirmed() { return confirmed; } public void setConfirmed(boolean v) { confirmed = v; }
        public int getPosition() { return position; } public void setPosition(int v) { position = v; }
    }

    @Entity(name = "PlayEntity") @Table(name = "plays")
    public static class PlayEntity {
        @Id private String id;
        @Column(name = "lesson_id", nullable = false) private String lessonId;
        @Column(nullable = false) private int level; @Column(nullable = false) private int variant;
        @Column(name = "play_json", nullable = false) private String playJson;
        @Column(name = "prompt_version", nullable = false) private String promptVersion;
        @Column(nullable = false) private int seed;
        @Column(name = "generated_at", nullable = false) private Instant generatedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public int getLevel() { return level; } public void setLevel(int v) { level = v; }
        public int getVariant() { return variant; } public void setVariant(int v) { variant = v; }
        public String getPlayJson() { return playJson; } public void setPlayJson(String v) { playJson = v; }
        public String getPromptVersion() { return promptVersion; } public void setPromptVersion(String v) { promptVersion = v; }
        public int getSeed() { return seed; } public void setSeed(int v) { seed = v; }
        public Instant getGeneratedAt() { return generatedAt; } public void setGeneratedAt(Instant v) { generatedAt = v; }
    }

    @Entity(name = "StopEntity") @Table(name = "stops")
    public static class StopEntity {
        @Id private String id;
        @Column(name = "play_id", nullable = false) private String playId;
        @Column(name = "lesson_id", nullable = false) private String lessonId;
        @Column(nullable = false) private int position; @Column(nullable = false) private String type; @Column(nullable = false) private String category;
        @Column(nullable = false) private String title; @Column(nullable = false) private String ingredient;
        @Column(name = "content_json", nullable = false) private String contentJson;
        @Column(name = "parent_tip_en", nullable = false) private String parentTipEn; @Column(name = "parent_tip_ar", nullable = false) private String parentTipAr;
        /** CR5 (V9): the readable English the teacher last saved for this stop, and when. Null until she saves one. */
        @Column(name = "text") private String text; @Column(name = "text_updated_at") private Instant textUpdatedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getPlayId() { return playId; } public void setPlayId(String v) { playId = v; }
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public int getPosition() { return position; } public void setPosition(int v) { position = v; }
        public String getType() { return type; } public void setType(String v) { type = v; }
        public String getCategory() { return category; } public void setCategory(String v) { category = v; }
        public String getTitle() { return title; } public void setTitle(String v) { title = v; }
        public String getIngredient() { return ingredient; } public void setIngredient(String v) { ingredient = v; }
        public String getContentJson() { return contentJson; } public void setContentJson(String v) { contentJson = v; }
        public String getParentTipEn() { return parentTipEn; } public void setParentTipEn(String v) { parentTipEn = v; }
        public String getParentTipAr() { return parentTipAr; } public void setParentTipAr(String v) { parentTipAr = v; }
        public String getText() { return text; } public void setText(String v) { text = v; }
        public Instant getTextUpdatedAt() { return textUpdatedAt; } public void setTextUpdatedAt(Instant v) { textUpdatedAt = v; }
    }

    @Entity @Table(name = "parent_panels")
    public static class ParentPanelEntity {
        @Id @Column(name = "lesson_id") private String lessonId;
        @Column(name = "panel_json", nullable = false) private String panelJson;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public String getPanelJson() { return panelJson; } public void setPanelJson(String v) { panelJson = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
    }

    @Entity @Table(name = "page_images")
    public static class PageImageEntity {
        @Id private String id;
        @Column(name = "lesson_id", nullable = false) private String lessonId;
        @Column(name = "page_number", nullable = false) private int pageNumber;
        @Column(name = "storage_path", nullable = false) private String storagePath;
        @Column(nullable = false) private int width; @Column(nullable = false) private int height;
        @Column(nullable = false) private String description = "";
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public int getPageNumber() { return pageNumber; } public void setPageNumber(int v) { pageNumber = v; }
        public String getStoragePath() { return storagePath; } public void setStoragePath(String v) { storagePath = v; }
        public int getWidth() { return width; } public void setWidth(int v) { width = v; }
        public int getHeight() { return height; } public void setHeight(int v) { height = v; }
        public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    }

    /** One pipeline step of a lesson: pending → running → done | error, with the attempt count and the last error. */
    @Entity @Table(name = "lesson_steps")
    public static class LessonStepEntity {
        @Id private String id;
        @Column(name = "lesson_id", nullable = false) private String lessonId;
        @Column(nullable = false) private String step;
        @Column(nullable = false) private int position;
        @Column(nullable = false) private String status;
        @Column(nullable = false) private int attempt;
        @Column(name = "error_code") private String errorCode; @Column(name = "error_message") private String errorMessage;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public String getStep() { return step; } public void setStep(String v) { step = v; }
        public int getPosition() { return position; } public void setPosition(int v) { position = v; }
        public String getStatus() { return status; } public void setStatus(String v) { status = v; }
        public int getAttempt() { return attempt; } public void setAttempt(int v) { attempt = v; }
        public String getErrorCode() { return errorCode; } public void setErrorCode(String v) { errorCode = v; }
        public String getErrorMessage() { return errorMessage; } public void setErrorMessage(String v) { errorMessage = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
    }
}
