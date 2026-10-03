package quest.server.content;

import java.util.List;
import java.util.stream.Collectors;
import quest.api.dto.Bilingual;
import quest.api.dto.Stop;
import quest.api.dto.Tile;

/**
 * CR5: a stop, read out as English a teacher understands, with no model call.
 *
 * <p><strong>Why it is deterministic.</strong> The owner's ask was that a teacher never sees JSON. The obvious
 * implementation — ask the model to describe the stop — costs a call and a wait on every lesson she opens, and two
 * openings of the same unedited stop would read differently. So the JSON → text direction is code, and only the
 * text → JSON direction (which genuinely needs to understand what she wrote) is a model call. That also makes the
 * fallback honest: `stops.text` is null until she saves, and until then what she reads is generated from the JSON
 * that is actually stored, so it can never claim something the app will not play.
 *
 * <p><strong>The shape.</strong> A heading line (the title), the sentence Pip speaks, then the stop's own content
 * as short labelled paragraphs and lists — the correct answer marked, pairs joined, an order numbered, an exit
 * ticket's questions described the same way one rung in. The parent tip closes it in both languages, which is the
 * one place a stop carries Arabic. Nothing here is parsed back: the reverse direction is Prompt D, which is given
 * the current JSON as well, so a line this renderer words loosely still cannot lose a field.
 */
public final class StopText {
    private StopText() {}
    private static final String CORRECT = " (correct)";

    public static String describe(Stop stop) { var sb = new StringBuilder(); describe(stop, sb, ""); return sb.toString().strip(); }

    private static void describe(Stop stop, StringBuilder sb, String pad) {
        line(sb, pad, stop.getTitle());
        line(sb, pad, "Pip says: " + stop.getSpeak());
        if (stop.getImageId() != null) line(sb, pad, "Picture: " + stop.getImageId());
        line(sb, pad, "");
        switch (stop) {
            case Stop.ReadPage s -> {
                line(sb, pad, "Read page " + s.getPageNumber() + ".");
                if (s.getPictureDescription() != null) line(sb, pad, "The picture shows: " + s.getPictureDescription());
                bullets(sb, pad, s.getSentences());
                if (s.getTapTask() != null) {
                    line(sb, pad, ""); line(sb, pad, "Tap task: " + s.getTapTask().getPrompt());
                    for (var h : s.getTapTask().getHotspots()) line(sb, pad, "- " + h.getLabel() + (s.getTapTask().getCorrectIds().contains(h.getId()) ? CORRECT : ""));
                }
            }
            case Stop.StoryPieces s -> { line(sb, pad, "Story pieces:"); for (var c : s.getCards()) line(sb, pad, "- " + c.getPiece() + ": " + c.getDefinition() + " — " + c.getAnswer()); }
            case Stop.WordCards s -> { line(sb, pad, "Words:"); for (var w : s.getWords()) line(sb, pad, "- " + w.getWord() + " means " + w.getMeaning() + ". Example: " + w.getSentence()); }
            case Stop.Move s -> { line(sb, pad, "Actions:"); for (var a : s.getActions()) line(sb, pad, "- " + a.getEmoji() + " " + a.getText()); }
            case Stop.Explain s -> {
                line(sb, pad, "Skill: " + s.getSkillId());
                line(sb, pad, s.getExplanation());
                for (var w : s.getWorkedExamples()) {
                    line(sb, pad, ""); line(sb, pad, "Worked example: " + w.getPrompt());
                    int n = 1; for (String step : w.getSteps()) line(sb, pad, "  " + n++ + ". " + step);
                    line(sb, pad, "  Answer: " + w.getAnswer());
                }
            }
            case Stop.Choice s -> { line(sb, pad, "Question: " + s.getQuestion()); tiles(sb, pad, s.getOptions(), List.of(s.getCorrectOptionId())); hint(sb, pad, s.getHint()); }
            case Stop.TrueFalse s -> { line(sb, pad, "Statement: " + s.getStatement()); line(sb, pad, "The statement is " + (s.getAnswer() ? "true" : "false") + "."); hint(sb, pad, s.getHint()); }
            case Stop.Sequence s -> {
                line(sb, pad, "The sequence: " + s.getChips().stream().map(c -> c == null ? "___" : String.valueOf(c)).collect(Collectors.joining(", ")));
                options(sb, pad, labels(s.getOptions()), ids(s.getOptions()), s.getCorrectOptionId());
                numberLine(sb, pad, s.getNumberLine()); hint(sb, pad, s.getHint());
            }
            case Stop.Count s -> {
                line(sb, pad, "Count the " + s.getObjectKey() + ": groups of " + s.getGroupSizes().stream().map(String::valueOf).collect(Collectors.joining(", ")) + " (" + s.getTotal() + " altogether).");
                options(sb, pad, labels(s.getOptions()), ids(s.getOptions()), s.getCorrectOptionId());
                numberLine(sb, pad, s.getNumberLine()); hint(sb, pad, s.getHint());
            }
            case Stop.Compare s -> {
                line(sb, pad, "Compare " + s.getLeft() + " and " + s.getRight() + ".");
                options(sb, pad, labels(s.getOptions()), ids(s.getOptions()), s.getCorrectOptionId());
                numberLine(sb, pad, s.getNumberLine()); hint(sb, pad, s.getHint());
            }
            case Stop.Sound s -> { line(sb, pad, "The picture is: " + s.getIllustrationKey()); options(sb, pad, labels(s.getOptions()), ids(s.getOptions()), s.getCorrectOptionId()); hint(sb, pad, s.getHint()); }
            case Stop.Word s -> { line(sb, pad, "The word Pip says: " + s.getSpokenWord()); options(sb, pad, labels(s.getOptions()), ids(s.getOptions()), s.getCorrectOptionId()); hint(sb, pad, s.getHint()); }
            case Stop.ReadTap s -> {
                line(sb, pad, "The word to read: " + s.getWord());
                options(sb, pad, s.getOptions().stream().map(quest.api.dto.PictureOption::getIllustrationKey).toList(), s.getOptions().stream().map(quest.api.dto.PictureOption::getId).toList(), s.getCorrectOptionId());
                hint(sb, pad, s.getHint());
            }
            case Stop.MultiSelect s -> { line(sb, pad, s.getPrompt()); line(sb, pad, "Pick " + s.getPick() + ":"); tiles(sb, pad, s.getOptions(), s.getCorrectIds()); }
            case Stop.SelectAll s -> { line(sb, pad, s.getPrompt()); line(sb, pad, "Tap every one that fits:"); tiles(sb, pad, s.getOptions(), s.getCorrectIds()); }
            case Stop.Match s -> { line(sb, pad, s.getPrompt()); line(sb, pad, "Pairs:"); for (var p : s.getPairs()) line(sb, pad, "- " + tile(p.getLeft()) + " goes with " + tile(p.getRight())); }
            case Stop.Order s -> {
                line(sb, pad, s.getPrompt()); line(sb, pad, "The right order:");
                int n = 1;
                for (String id : s.getCorrectOrder()) line(sb, pad, "  " + n++ + ". " + s.getItems().stream().filter(i -> i.getId().equals(id)).findFirst().map(quest.api.dto.OrderItem::getText).orElse(id));
            }
            case Stop.Trace s -> { line(sb, pad, "Trace: " + s.getText()); hint(sb, pad, s.getHint()); }
            case Stop.Retell s -> {
                line(sb, pad, s.getPrompt()); line(sb, pad, "Cues:");
                for (var c : s.getCues()) line(sb, pad, "- " + c.getStage() + ": " + c.getCue());
                line(sb, pad, "Model answer: " + s.getModelAnswer());
                line(sb, pad, s.getRecord() ? "The child records the answer." : "The child answers out loud; nothing is recorded.");
            }
            case Stop.OpenAnswer s -> { line(sb, pad, s.getPrompt()); line(sb, pad, "Answered by: " + s.getMode()); line(sb, pad, "Model answer: " + s.getModelAnswer()); }
            case Stop.WriteSentence s -> {
                line(sb, pad, "Sentence frame: " + s.getFrame()); line(sb, pad, "Answer: " + s.getAnswer());
                if (s.getOptions() != null && !s.getOptions().isEmpty()) line(sb, pad, "Word bank: " + String.join(", ", s.getOptions()));
                if (s.getFree()) line(sb, pad, "The child writes freely.");
            }
            case Stop.ExitTicket s -> {
                line(sb, pad, "Exit ticket — " + s.getQuestions().size() + " question" + (s.getQuestions().size() == 1 ? "" : "s") + ":");
                for (var q : s.getQuestions()) { line(sb, pad, ""); describe(q, sb, pad + "  "); }
            }
            default -> line(sb, pad, "(no readable description for a " + stop.getType() + " stop yet)");
        }
        line(sb, pad, "");
        line(sb, pad, "Ingredient: " + stop.getIngredient().getEmoji() + " " + stop.getIngredient().getName());
        parentTip(sb, pad, stop.getParentTip());
    }

    // ---------------------------------------------------------------- pieces
    private static void line(StringBuilder sb, String pad, String text) { sb.append(text.isEmpty() ? "" : pad + text).append('\n'); }
    private static void hint(StringBuilder sb, String pad, String hint) { if (!hint.isBlank()) line(sb, pad, "Hint: " + hint); }
    private static void bullets(StringBuilder sb, String pad, List<String> items) { for (String i : items) line(sb, pad, "- " + i); }
    private static List<String> labels(List<quest.api.dto.Option> options) { return options.stream().map(quest.api.dto.Option::getLabel).toList(); }
    private static List<String> ids(List<quest.api.dto.Option> options) { return options.stream().map(quest.api.dto.Option::getId).toList(); }

    private static void options(StringBuilder sb, String pad, List<String> labels, List<String> ids, String correctId) {
        line(sb, pad, "Options:");
        for (int i = 0; i < labels.size(); i++) line(sb, pad, "- " + labels.get(i) + (ids.get(i).equals(correctId) ? CORRECT : ""));
    }

    private static void tiles(StringBuilder sb, String pad, List<Tile> options, List<String> correctIds) {
        line(sb, pad, "Options:");
        for (Tile t : options) line(sb, pad, "- " + tile(t) + (correctIds.contains(t.getId()) ? CORRECT : ""));
    }

    /** A tile is text, a drawing or a crop of the page; whichever it carries is what the teacher is shown. */
    private static String tile(Tile t) {
        if (t.getLabel() != null && !t.getLabel().isBlank()) return t.getLabel();
        if (t.getIllustrationKey() != null) return "the " + t.getIllustrationKey() + " picture";
        return t.getPageImageId() != null ? "the page picture " + t.getPageImageId() : t.getId();
    }

    private static void numberLine(StringBuilder sb, String pad, quest.api.dto.NumberLine n) {
        String highlight = n.getHighlight().isEmpty() ? "" : ", marking " + n.getHighlight().stream().map(String::valueOf).collect(Collectors.joining(", "));
        line(sb, pad, "Number line: " + n.getFrom() + " to " + n.getTo() + " in steps of " + n.getStep() + highlight + ".");
    }

    private static void parentTip(StringBuilder sb, String pad, Bilingual tip) {
        line(sb, pad, "Parent tip (English): " + tip.getEn());
        line(sb, pad, "Parent tip (Arabic): " + tip.getAr());
    }
}
