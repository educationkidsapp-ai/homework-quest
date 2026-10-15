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
        if (system.equals(Prompts.SMART_HINTS)) return hints(user);
        if (system.equals(Prompts.TARGETED_PRACTICE)) return practice(user);
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

    /**
     * Feature 30's hints without a model: two fixed, method-shaped hints for every question the prompt lists, keyed
     * by the `stopId` it was given. Deterministic, which is what lets the API test press Build and assert on what
     * the teacher is then shown.
     *
     * <p><strong>The last question is answered badly on purpose.</strong> Its second hint says the answer out loud —
     * the one mistake the real model makes often enough that the owner asked for a test — so the whole of
     * {@link quest.server.hints.HintRules}'s reject, retry and drop runs end to end on every build. The retry turn
     * is answered the same way, so that stop ends with no hints at all and every other stop with two.
     */
    private Result hints(String user) {
        var questions = new java.util.ArrayList<String[]>();           // [stopId, the first thing it may not say]
        var stop = STOP_ID.matcher(user);
        while (stop.find()) questions.add(new String[] { stop.group(1), firstDoNotSay(user, stop.end()) });
        var out = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        var arr = out.putArray("hints");
        for (int i = 0; i < questions.size(); i++) {
            String[] q = questions.get(i);
            var entry = arr.addObject(); entry.put("stopId", q[0]);
            var two = entry.putArray("hints");
            two.add("Look at the question once more, slowly.");
            boolean last = i == questions.size() - 1;
            // the deliberate mistake: the answer out loud when the prompt named one, and otherwise a hint far past
            // the 90-character limit — either way the last question's hints are rejected, asked for again and dropped
            two.add(!last ? "Think about the way your teacher showed you first."
                    : q[1] != null && !q[1].isBlank() ? "The answer is " + q[1] + "."
                    : "This hint goes on and on and on and on and on and on and on and on, well past every limit it was given.");
        }
        return new Result(out.toString(), 1500, 900);
    }

    private static final java.util.regex.Pattern STOP_ID = java.util.regex.Pattern.compile("\"stopId\":\"([^\"]+)\"");
    private static final java.util.regex.Pattern DO_NOT_SAY = java.util.regex.Pattern.compile("\"doNotSay\":\\[\"([^\"]*)\"");

    /** The first `doNotSay` word after this question's `stopId`, or null when the question has none. */
    private static String firstDoNotSay(String user, int from) {
        int next = user.indexOf("\"stopId\"", from);
        var m = DO_NOT_SAY.matcher(next < 0 ? user.substring(from) : user.substring(from, next));
        return m.find() ? m.group(1) : null;
    }

    /**
     * Feature 30's practice set without a model: four of the seeded lesson's own answerable questions, under the
     * variant the prompt asked for and with ids of their own — which is exactly what the shared validator calls a
     * practice play, so the step's accept path runs for real.
     */
    private Result practice(String user) {
        var codec = SchemaValidator.INSTANCE.getJson();
        boolean math = user.contains("\"kind\":\"math\"") || user.contains("\"subject\":\"math\"");
        boolean phonics = !math && user.contains("\"kind\":\"phonics\"");
        var lesson = math ? MathSeed.INSTANCE.getLesson() : phonics ? PhonicsSeed.INSTANCE.getLesson() : HotSoupSeed.INSTANCE.getLesson();
        int variant = variantOf(user);
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(codec.encodeToString(Play.Companion.serializer(), lesson.getPlays().get(0)));
            var kept = mapper.createArrayNode();
            for (var candidate : answerable((com.fasterxml.jackson.databind.node.ArrayNode) node.get("stops"))) {
                if (kept.size() == PRACTICE_STOPS) break;
                var copy = candidate.deepCopy();
                copy.put("id", "p" + variant + (kept.size() + 1));
                kept.add(copy);
            }
            node.set("stops", kept); node.put("level", 1); node.put("variant", variant);
            node.remove("id");
            return new Result(node.toString(), 2200, 1400);
        } catch (Exception e) { throw new LlmException("the sample client could not write a practice set", e); }
    }

    /** As many stops as a practice set holds. */
    private static final int PRACTICE_STOPS = 4;
    /** The stop types a practice set is made of — single- and multi-answer, which is what the validator asks for. */
    private static final List<String> ANSWERABLE = List.of("choice", "trueFalse", "sequence", "count", "compare",
            "sound", "word", "readTap", "multiSelect", "selectAll", "match", "order", "trace");

    /** The answerable stops of a play as JSON, the three inside its exitTicket included. */
    private static List<com.fasterxml.jackson.databind.node.ObjectNode> answerable(com.fasterxml.jackson.databind.node.ArrayNode stops) {
        var out = new java.util.ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
        for (var stop : stops) {
            if (ANSWERABLE.contains(stop.path("type").asText())) out.add((com.fasterxml.jackson.databind.node.ObjectNode) stop);
            if ("exitTicket".equals(stop.path("type").asText()))
                for (var question : stop.path("questions")) if (ANSWERABLE.contains(question.path("type").asText())) out.add((com.fasterxml.jackson.databind.node.ObjectNode) question);
        }
        return out;
    }

    private static final java.util.regex.Pattern VARIANT = java.util.regex.Pattern.compile("\"variant\": (\\d+)");

    private static int variantOf(String user) {
        var m = VARIANT.matcher(user);
        return m.find() ? Integer.parseInt(m.group(1)) : quest.api.dto.Play.PRACTICE_VARIANT;
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
