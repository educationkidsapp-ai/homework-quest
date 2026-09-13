package quest.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "lessons")
public class LessonEntity {
    @Id private String id;
    @Column(name = "child_id") private String childId;
    @Column(nullable = false) private LocalDate date;
    @Column(nullable = false) private String subject;
    @Column(nullable = false) private int grade;
    @Column(nullable = false) private String curriculum;
    @Column(name = "practice_length", nullable = false) private int practiceLength = 7;
    @Column(nullable = false) private String status;
    @Column(name = "error_code") private String errorCode;
    @Column(name = "error_message") private String errorMessage;
    @Column(name = "source_file_names", nullable = false) private String sourceFileNames = "[]";
    @Column(name = "typed_task") private String typedTask;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getChildId() { return childId; }
    public void setChildId(String childId) { this.childId = childId; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public int getGrade() { return grade; }
    public void setGrade(int grade) { this.grade = grade; }
    public String getCurriculum() { return curriculum; }
    public void setCurriculum(String curriculum) { this.curriculum = curriculum; }
    public int getPracticeLength() { return practiceLength; }
    public void setPracticeLength(int practiceLength) { this.practiceLength = practiceLength; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getSourceFileNames() { return sourceFileNames; }
    public void setSourceFileNames(String sourceFileNames) { this.sourceFileNames = sourceFileNames; }
    public String getTypedTask() { return typedTask; }
    public void setTypedTask(String typedTask) { this.typedTask = typedTask; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
