package quest.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "question_sets")
public class QuestionSetEntity {
    @Id private String id;
    @Column(name = "skill_id", nullable = false) private String skillId;
    @Column(nullable = false) private String mode;
    @Column(nullable = false) private String seed;
    @Column(nullable = false) private String explanation;
    @Column(name = "worked_examples_json", nullable = false) private String workedExamplesJson;
    @Column(name = "generated_at", nullable = false) private Instant generatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getSeed() { return seed; }
    public void setSeed(String seed) { this.seed = seed; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public String getWorkedExamplesJson() { return workedExamplesJson; }
    public void setWorkedExamplesJson(String workedExamplesJson) { this.workedExamplesJson = workedExamplesJson; }
    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
}
