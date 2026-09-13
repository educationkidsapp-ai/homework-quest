package quest.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "skills")
public class SkillEntity {
    @Id private String id;
    @Column(name = "lesson_id", nullable = false) private String lessonId;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private String subject;
    @Column(nullable = false) private String method;
    @Column(name = "examples_json", nullable = false) private String examplesJson = "[]";
    @Column(name = "slide_numbers_json", nullable = false) private String slideNumbersJson = "[]";
    @Column(nullable = false) private double confidence;
    @Column(name = "unsure_json") private String unsureJson;
    @Column(nullable = false) private boolean confirmed;
    @Column(nullable = false) private int position;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLessonId() { return lessonId; }
    public void setLessonId(String lessonId) { this.lessonId = lessonId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getExamplesJson() { return examplesJson; }
    public void setExamplesJson(String examplesJson) { this.examplesJson = examplesJson; }
    public String getSlideNumbersJson() { return slideNumbersJson; }
    public void setSlideNumbersJson(String slideNumbersJson) { this.slideNumbersJson = slideNumbersJson; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getUnsureJson() { return unsureJson; }
    public void setUnsureJson(String unsureJson) { this.unsureJson = unsureJson; }
    public boolean isConfirmed() { return confirmed; }
    public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
}
