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
        if (system.equals(Prompts.SYSTEM_E)) return announcementFromTranscript(user);
        if (system.equals(Prompts.TRANSLATE)) return markedTranslation(user);
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

    /**
     * Feature 22's turn without a model: the first line of the transcript becomes the title, the whole transcript
     * becomes both bodies, the <em>first</em> audience line offered is copied into `audience`, and an ISO date written
     * anywhere in the transcript becomes `expiresOn`. Deterministic, which is what lets the API test and the local H2
     * run press the button and assert on what comes back — and it exercises the real checks either way, because the
     * server still has to recognise that first cell as one of the caller's own.
     */
    private Result announcementFromTranscript(String user) {
        String cells = between(user, "out for all of them):\n", "\n\nWHAT SHE SAID");
        String transcript = between(user, "punctuation may be missing):\n", "\n\nYour previous answer").strip();
        List<String> lines = transcript.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        var out = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        out.put("title", cut(lines.isEmpty() ? "Announcement" : lines.get(0), 120));
        out.put("bodyAr", cut(transcript, 1000));
        out.put("bodyEn", cut(transcript, 1000));
        var cell = CELL.matcher(cells);
        if (cell.find()) out.putObject("audience").put("curriculum", cell.group(1)).put("grade", Integer.parseInt(cell.group(2)));
        var day = ISO_DAY.matcher(transcript);
        if (day.find()) out.put("expiresOn", day.group());
        return new Result(out.toString(), 700, 400);
    }

    /**
     * Feature 29's turn without a model: the source text with the target language's code in front of it, e.g.
     * `[ur] Please send her reading book tomorrow.`. A marker rather than a real translation, and deliberately so —
     * it is deterministic, it carries the source through so a test can see that nothing was lost, it adds no link,
     * and it stays inside the validator's length bound for any source. What the API test then proves is the
     * machinery: the cache, the hash, who may ask, and that the original is untouched.
     */
    private Result markedTranslation(String user) {
        var code = LANG.matcher(between(user, "TRANSLATE INTO: ", "\n"));
        String text = between(user, "nothing but the translation):\n", "\n\nYour previous answer");
        return new Result("[" + (code.find() ? code.group(1) : "xx") + "] " + text, 300, 200);
    }

    /** The code in `Urdu (ur)`, which {@link Prompts#userTranslate} is given. */
    private static final java.util.regex.Pattern LANG = java.util.regex.Pattern.compile("\\(([a-z]{2,3})\\)");

    /** One audience line of {@link Prompts#userE}: `curriculum "british", grade 1`. */
    private static final java.util.regex.Pattern CELL = java.util.regex.Pattern.compile("curriculum \"([a-z]+)\", grade (\\d+)");
    private static final java.util.regex.Pattern ISO_DAY = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static String cut(String s, int max) { return s.length() <= max ? s : s.substring(0, max).strip(); }

    private static String between(String whole, String after, String before) {
        int from = whole.indexOf(after); if (from < 0) return "{}";
        from += after.length();
        int to = whole.indexOf(before, from);
        return (to < 0 ? whole.substring(from) : whole.substring(from, to)).strip();
    }
}
