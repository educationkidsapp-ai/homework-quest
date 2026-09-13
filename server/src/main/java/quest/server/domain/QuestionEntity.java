package quest.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "questions")
public class QuestionEntity {
    @Id private String id;
    @Column(name = "question_set_id", nullable = false) private String questionSetId;
    @Column(nullable = false) private int position;
    @Column(nullable = false) private String type;
    @Column(name = "prompt_json", nullable = false) private String promptJson;
    @Column(name = "options_json", nullable = false) private String optionsJson;
    @Column(name = "correct_option_id") private String correctOptionId;
    @Column(nullable = false) private String hint;
    @Column(name = "number_line_json") private String numberLineJson;
    @Column(name = "illustration_key") private String illustrationKey;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getQuestionSetId() { return questionSetId; }
    public void setQuestionSetId(String questionSetId) { this.questionSetId = questionSetId; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPromptJson() { return promptJson; }
    public void setPromptJson(String promptJson) { this.promptJson = promptJson; }
    public String getOptionsJson() { return optionsJson; }
    public void setOptionsJson(String optionsJson) { this.optionsJson = optionsJson; }
    public String getCorrectOptionId() { return correctOptionId; }
    public void setCorrectOptionId(String correctOptionId) { this.correctOptionId = correctOptionId; }
    public String getHint() { return hint; }
    public void setHint(String hint) { this.hint = hint; }
    public String getNumberLineJson() { return numberLineJson; }
    public void setNumberLineJson(String numberLineJson) { this.numberLineJson = numberLineJson; }
    public String getIllustrationKey() { return illustrationKey; }
    public void setIllustrationKey(String illustrationKey) { this.illustrationKey = illustrationKey; }
}
