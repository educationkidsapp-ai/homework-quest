package quest.server.children;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

public final class Entities {
    private Entities() {}

    /**
     * Tenant table: every read through a scoped request is filtered to the caller's school (`quest.server.tenancy`).
     *
     * <p>Since V7 a child is first of all a <strong>roster row</strong> of a section (`classId`) — Admin or her
     * teacher types the name long before anyone has an account for her, so `parentId` is nullable and `active` is
     * how a roster row is retired without deleting the attempts hanging off it. `name` is the full name the roster
     * carries; `deletedAt` stays the parent-side soft delete it always was.
     */
    @Entity(name = "ChildEntity") @Table(name = "children")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class ChildEntity {
        @Id private String id;
        @Column(name = "parent_id") private String parentId;
        @Column(name = "school_id", nullable = false) private String schoolId = "default";
        @Column(nullable = false) private String name; @Column(name = "avatar_color", nullable = false) private String avatarColor;
        @Column(nullable = false) private String curriculum; @Column(nullable = false) private int grade;
        @Column(nullable = false) private String languages = "en";
        @Column(name = "pin_hash") private String pinHash;
        @Column(name = "class_id") private String classId;
        @Column(name = "parent_email") private String parentEmail;
        @Column(name = "photo_url") private String photoUrl;
        @Column(nullable = false) private boolean active = true;
        @Column(name = "created_at", nullable = false) private Instant createdAt; @Column(name = "deleted_at") private Instant deletedAt;
        public String courseId() { return curriculum + "/" + grade; }
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getParentId() { return parentId; } public void setParentId(String v) { parentId = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getName() { return name; } public void setName(String v) { name = v; }
        public String getAvatarColor() { return avatarColor; } public void setAvatarColor(String v) { avatarColor = v; }
        public String getCurriculum() { return curriculum; } public void setCurriculum(String v) { curriculum = v; }
        public int getGrade() { return grade; } public void setGrade(int v) { grade = v; }
        public String getLanguages() { return languages; } public void setLanguages(String v) { languages = v; }
        public String getPinHash() { return pinHash; } public void setPinHash(String v) { pinHash = v; }
        public String getClassId() { return classId; } public void setClassId(String v) { classId = v; }
        public String getParentEmail() { return parentEmail; } public void setParentEmail(String v) { parentEmail = v; }
        public String getPhotoUrl() { return photoUrl; } public void setPhotoUrl(String v) { photoUrl = v; }
        public boolean isActive() { return active; } public void setActive(boolean v) { active = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public Instant getDeletedAt() { return deletedAt; } public void setDeletedAt(Instant v) { deletedAt = v; }
    }

    /** Named for JPQL like its neighbours: a nested `@Entity` has no usable default name in a query. */
    @Entity(name = "AttemptEntity") @Table(name = "attempts")
    public static class AttemptEntity {
        @Id private String id;
        @Column(name = "child_id", nullable = false) private String childId;
        @Column(name = "stop_id", nullable = false) private String stopId; @Column(name = "lesson_id", nullable = false) private String lessonId;
        @Column(nullable = false) private int level;
        @Column(name = "answer_json", nullable = false) private String answerJson;
        @Column(nullable = false) private boolean correct;
        @Column(name = "attempt_number", nullable = false) private int attemptNumber;
        @Column(nullable = false) private int mistakes; @Column(nullable = false) private int stars;
        @Column(name = "answered_at", nullable = false) private Instant answeredAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getChildId() { return childId; } public void setChildId(String v) { childId = v; }
        public String getStopId() { return stopId; } public void setStopId(String v) { stopId = v; }
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public int getLevel() { return level; } public void setLevel(int v) { level = v; }
        public String getAnswerJson() { return answerJson; } public void setAnswerJson(String v) { answerJson = v; }
        public boolean isCorrect() { return correct; } public void setCorrect(boolean v) { correct = v; }
        public int getAttemptNumber() { return attemptNumber; } public void setAttemptNumber(int v) { attemptNumber = v; }
        public int getMistakes() { return mistakes; } public void setMistakes(int v) { mistakes = v; }
        public int getStars() { return stars; } public void setStars(int v) { stars = v; }
        public Instant getAnsweredAt() { return answeredAt; } public void setAnsweredAt(Instant v) { answeredAt = v; }
    }

    public static class StopCompletionId implements Serializable { public String childId; public String stopId;
        public StopCompletionId() {} public StopCompletionId(String c, String s) { childId = c; stopId = s; }
        @Override public boolean equals(Object o) { return o instanceof StopCompletionId x && x.childId.equals(childId) && x.stopId.equals(stopId); }
        @Override public int hashCode() { return (childId + "/" + stopId).hashCode(); } }

    @Entity @Table(name = "stop_completions") @IdClass(StopCompletionId.class)
    public static class StopCompletionEntity {
        @Id @Column(name = "child_id") private String childId; @Id @Column(name = "stop_id") private String stopId;
        @Column(name = "lesson_id", nullable = false) private String lessonId; @Column(nullable = false) private int level; @Column(nullable = false) private int stars;
        @Column(name = "completed_at", nullable = false) private Instant completedAt;
        @Column(name = "recording_path") private String recordingPath; @Column(name = "drawing_path") private String drawingPath;
        public String getChildId() { return childId; } public void setChildId(String v) { childId = v; }
        public String getStopId() { return stopId; } public void setStopId(String v) { stopId = v; }
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public int getLevel() { return level; } public void setLevel(int v) { level = v; }
        public int getStars() { return stars; } public void setStars(int v) { stars = v; }
        public Instant getCompletedAt() { return completedAt; } public void setCompletedAt(Instant v) { completedAt = v; }
        public String getRecordingPath() { return recordingPath; } public void setRecordingPath(String v) { recordingPath = v; }
        public String getDrawingPath() { return drawingPath; } public void setDrawingPath(String v) { drawingPath = v; }
    }

    public static class LessonCompletionId implements Serializable { public String childId; public String lessonId; public int level;
        public LessonCompletionId() {} public LessonCompletionId(String c, String l, int lv) { childId = c; lessonId = l; level = lv; }
        @Override public boolean equals(Object o) { return o instanceof LessonCompletionId x && x.childId.equals(childId) && x.lessonId.equals(lessonId) && x.level == level; }
        @Override public int hashCode() { return (childId + "/" + lessonId + "/" + level).hashCode(); } }

    @Entity @Table(name = "lesson_completions") @IdClass(LessonCompletionId.class)
    public static class LessonCompletionEntity {
        @Id @Column(name = "child_id") private String childId; @Id @Column(name = "lesson_id") private String lessonId; @Id private int level;
        @Column(name = "stars_earned", nullable = false) private int starsEarned; @Column(name = "stars_total", nullable = false) private int starsTotal;
        @Column(name = "most_stops_two_stars", nullable = false) private boolean mostStopsTwoStars;
        @Column(name = "certificate_issued", nullable = false) private boolean certificateIssued = true;
        @Column(name = "completed_at", nullable = false) private Instant completedAt;
        public String getChildId() { return childId; } public void setChildId(String v) { childId = v; }
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public int getLevel() { return level; } public void setLevel(int v) { level = v; }
        public int getStarsEarned() { return starsEarned; } public void setStarsEarned(int v) { starsEarned = v; }
        public int getStarsTotal() { return starsTotal; } public void setStarsTotal(int v) { starsTotal = v; }
        public boolean isMostStopsTwoStars() { return mostStopsTwoStars; } public void setMostStopsTwoStars(boolean v) { mostStopsTwoStars = v; }
        public boolean isCertificateIssued() { return certificateIssued; } public void setCertificateIssued(boolean v) { certificateIssued = v; }
        public Instant getCompletedAt() { return completedAt; } public void setCompletedAt(Instant v) { completedAt = v; }
    }

    public static class ParentUnlockId implements Serializable { public String childId; public String lessonId; public int level;
        public ParentUnlockId() {} public ParentUnlockId(String c, String l, int lv) { childId = c; lessonId = l; level = lv; }
        @Override public boolean equals(Object o) { return o instanceof ParentUnlockId x && x.childId.equals(childId) && x.lessonId.equals(lessonId) && x.level == level; }
        @Override public int hashCode() { return (childId + "/" + lessonId + "/" + level).hashCode(); } }

    @Entity @Table(name = "parent_unlocks") @IdClass(ParentUnlockId.class)
    public static class ParentUnlockEntity {
        @Id @Column(name = "child_id") private String childId; @Id @Column(name = "lesson_id") private String lessonId; @Id private int level;
        public String getChildId() { return childId; } public void setChildId(String v) { childId = v; }
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public int getLevel() { return level; } public void setLevel(int v) { level = v; }
    }

    @Entity @Table(name = "stickers")
    public static class StickerEntity {
        @Id private String id; @Column(name = "child_id", nullable = false) private String childId; @Column(name = "sticker_key", nullable = false) private String stickerKey; @Column(name = "earned_at", nullable = false) private Instant earnedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getChildId() { return childId; } public void setChildId(String v) { childId = v; }
        public String getStickerKey() { return stickerKey; } public void setStickerKey(String v) { stickerKey = v; }
        public Instant getEarnedAt() { return earnedAt; } public void setEarnedAt(Instant v) { earnedAt = v; }
    }

    @Entity @Table(name = "streaks")
    public static class StreakEntity {
        @Id @Column(name = "child_id") private String childId; @Column(name = "current_days", nullable = false) private int currentDays; @Column(name = "last_played_date") private LocalDate lastPlayedDate;
        public String getChildId() { return childId; } public void setChildId(String v) { childId = v; }
        public int getCurrentDays() { return currentDays; } public void setCurrentDays(int v) { currentDays = v; }
        public LocalDate getLastPlayedDate() { return lastPlayedDate; } public void setLastPlayedDate(LocalDate v) { lastPlayedDate = v; }
    }

    @Entity @Table(name = "child_media")
    public static class ChildMediaEntity {
        @Id private String id; @Column(name = "child_id", nullable = false) private String childId; @Column(name = "stop_id", nullable = false) private String stopId;
        @Column(nullable = false) private String kind; @Column(name = "storage_path", nullable = false) private String storagePath; @Column(name = "mime_type", nullable = false) private String mimeType;
        @Column(name = "size_bytes", nullable = false) private long sizeBytes; @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getChildId() { return childId; } public void setChildId(String v) { childId = v; }
        public String getStopId() { return stopId; } public void setStopId(String v) { stopId = v; }
        public String getKind() { return kind; } public void setKind(String v) { kind = v; }
        public String getStoragePath() { return storagePath; } public void setStoragePath(String v) { storagePath = v; }
        public String getMimeType() { return mimeType; } public void setMimeType(String v) { mimeType = v; }
        public long getSizeBytes() { return sizeBytes; } public void setSizeBytes(long v) { sizeBytes = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
