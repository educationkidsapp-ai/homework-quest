package quest.server.analysis;

import java.util.List;
import quest.api.dto.ParentPanel;
import quest.api.dto.Play;
import quest.api.dto.SourceAnalysis;
import quest.api.samples.HotSoupSeed;
import quest.api.samples.MathSeed;
import quest.api.samples.PhonicsSeed;
import quest.api.validation.SchemaValidator;

/** No network: answers with the seeded Hot Soup / math / phonics content. Used with FAKE_LLM=true and in tests. */
public class SampleLlmClient implements LlmClient {
    @Override public String name() { return "sample"; }

    @Override public Result complete(String system, String user, List<Attachment> attachments) {
        var json = SchemaValidator.INSTANCE.getJson();
        String header = user.substring(0, Math.min(400, user.length())).toLowerCase();   // "Course: … subject: math." + notes, or the analysis kind
        boolean math = header.contains("subject: math") || user.contains("\"kind\":\"math\"");
        boolean phonics = !math && (user.contains("\"kind\":\"phonics\"") || header.contains("phonics") || header.contains("sound"));
        if (system.equals(Prompts.SYSTEM_A)) {
            SourceAnalysis a = math ? MathSeed.INSTANCE.getAnalysis() : phonics ? PhonicsSeed.INSTANCE.getAnalysis() : HotSoupSeed.INSTANCE.getAnalysis();
            return new Result(json.encodeToString(SourceAnalysis.Companion.serializer(), a), 1200, 800);
        }
        if (system.equals(Prompts.SYSTEM_C)) {
            ParentPanel p = math ? MathSeed.INSTANCE.getParentPanel() : phonics ? PhonicsSeed.INSTANCE.getParentPanel() : HotSoupSeed.INSTANCE.getParentPanel();
            return new Result(json.encodeToString(ParentPanel.Companion.serializer(), p), 3000, 600);
        }
        if (system.startsWith(PROMPT_D)) return stopFromText(user);
        if (user.startsWith("Here is a Play you wrote earlier")) {
            int i = user.indexOf("different content:\n") + "different content:\n".length();
            String stop = user.substring(i, user.indexOf("\n\nDo not reuse", i)).trim();
            return new Result(stop.replaceFirst("\"id\":\"([^\"]+)\"", "\"id\":\"$1x\""), 2000, 400);
        }
        var lesson = math ? MathSeed.INSTANCE.getLesson() : phonics ? PhonicsSeed.INSTANCE.getLesson() : HotSoupSeed.INSTANCE.getLesson();
        int level = user.contains("LEVEL 2") ? 2 : user.contains("LEVEL 3") ? 3 : 1;
        boolean variant = user.contains("AGAIN variant");
        Play play = lesson.play(level, variant ? 1 : 0);
        return new Result(json.encodeToString(Play.Companion.serializer(), play), 4000, 2500);
    }

    /** The first line of {@link Prompts#SYSTEM_D}, which carries a schema and so cannot be matched whole. */
    private static final String PROMPT_D = "You turn a teacher's description of one practice stop";

    /**
     * CR5's text → JSON turn without a model: the stop exactly as it was stored, with the heading line the teacher
     * wrote as its `title` and her "Pip says:" line as `speak`. Deterministic, which is what lets the e2e press Save
     * and assert on the result; every other field survives, which is the behaviour the real Prompt D is asked for.
     */
    private Result stopFromText(String user) {
        String current = between(user, "did not ask you to change):\n", "\n\nWHAT THE TEACHER WROTE");
        String text = between(user, "follow the teacher):\n", "\n\nRules:");
        List<String> lines = text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        try {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(current);
            if (!lines.isEmpty()) node.put("title", cut(lines.get(0), 40));
            if (lines.size() > 1) node.put("speak", cut(lines.get(1).replaceFirst("^Pip says:\\s*", ""), 90));
            return new Result(node.toString(), 900, 300);
        } catch (Exception e) { return new Result(current, 900, 300); }
    }

    private static String cut(String s, int max) { return s.length() <= max ? s : s.substring(0, max).strip(); }

    private static String between(String whole, String after, String before) {
        int from = whole.indexOf(after); if (from < 0) return "{}";
        from += after.length();
        int to = whole.indexOf(before, from);
        return (to < 0 ? whole.substring(from) : whole.substring(from, to)).strip();
    }
}
