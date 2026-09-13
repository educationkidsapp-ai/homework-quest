package quest.server.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/** Prompt B output / generate response. Polymorphic on {@code type}, identical to the Kotlin sealed interface. */
public final class Questions {
    private Questions() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionSet(String id, String skillId, Enums.Mode mode, String explanation, List<WorkedExample> workedExamples, List<Question> questions) {
        public QuestionSet withId(String newId) { return new QuestionSet(newId, skillId, mode, explanation, workedExamples, questions); }
        public QuestionSet withSkillId(String newSkillId) { return new QuestionSet(id, newSkillId, mode, explanation, workedExamples, questions); }
    }

    public record WorkedExample(String prompt, List<String> steps, String answer) {}
    public record Option(String id, String label) {}
    public record PictureOption(String id, String illustrationKey) {}
    public record NumberLine(int from, int to, int step, List<Integer> highlight) {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Sequence.class, name = "sequence"),
            @JsonSubTypes.Type(value = Count.class, name = "count"),
            @JsonSubTypes.Type(value = Compare.class, name = "compare"),
            @JsonSubTypes.Type(value = Sound.class, name = "sound"),
            @JsonSubTypes.Type(value = Word.class, name = "word"),
            @JsonSubTypes.Type(value = Trace.class, name = "trace"),
            @JsonSubTypes.Type(value = ReadTap.class, name = "readTap"),
    })
    public sealed interface Question permits Sequence, Count, Compare, Sound, Word, Trace, ReadTap {
        String id();
        String hint();
        String typeName();
        Question withId(String newId);
        default String correctOptionId() { return null; }
        default String illustrationKey() { return null; }
        default NumberLine numberLine() { return null; }
        default List<String> optionIds() { return List.of(); }
    }

    public record Sequence(String id, String hint, List<Integer> chips, List<Option> options, String correctOptionId, NumberLine numberLine) implements Question {
        public String typeName() { return "sequence"; }
        public Question withId(String n) { return new Sequence(n, hint, chips, options, correctOptionId, numberLine); }
        public List<String> optionIds() { return options.stream().map(Option::id).toList(); }
    }
    public record Count(String id, String hint, String objectKey, List<Integer> groupSizes, List<Option> options, String correctOptionId, NumberLine numberLine) implements Question {
        public String typeName() { return "count"; }
        public Question withId(String n) { return new Count(n, hint, objectKey, groupSizes, options, correctOptionId, numberLine); }
        public String illustrationKey() { return objectKey; }
        public List<String> optionIds() { return options.stream().map(Option::id).toList(); }
    }
    public record Compare(String id, String hint, int left, int right, List<Option> options, String correctOptionId, NumberLine numberLine) implements Question {
        public String typeName() { return "compare"; }
        public Question withId(String n) { return new Compare(n, hint, left, right, options, correctOptionId, numberLine); }
        public List<String> optionIds() { return options.stream().map(Option::id).toList(); }
    }
    public record Sound(String id, String hint, String illustrationKey, List<Option> options, String correctOptionId) implements Question {
        public String typeName() { return "sound"; }
        public Question withId(String n) { return new Sound(n, hint, illustrationKey, options, correctOptionId); }
        public List<String> optionIds() { return options.stream().map(Option::id).toList(); }
    }
    public record Word(String id, String hint, String spokenWord, List<Option> options, String correctOptionId) implements Question {
        public String typeName() { return "word"; }
        public Question withId(String n) { return new Word(n, hint, spokenWord, options, correctOptionId); }
        public List<String> optionIds() { return options.stream().map(Option::id).toList(); }
    }
    public record Trace(String id, String hint, String letter) implements Question {
        public String typeName() { return "trace"; }
        public Question withId(String n) { return new Trace(n, hint, letter); }
    }
    public record ReadTap(String id, String hint, String word, List<PictureOption> options, String correctOptionId) implements Question {
        public String typeName() { return "readTap"; }
        public Question withId(String n) { return new ReadTap(n, hint, word, options, correctOptionId); }
        public String illustrationKey() { return word; }
        public List<String> optionIds() { return options.stream().map(PictureOption::id).toList(); }
    }
}
