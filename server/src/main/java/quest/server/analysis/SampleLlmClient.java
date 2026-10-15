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
        if (system.equals(Prompts.SYSTEM_F)) return briefFromFacts(user);
        if (system.equals(Prompts.DOCUMENT_READER)) return summaryFromDocument(user);
        if (system.equals(Prompts.COMPLAINT_TRIAGE)) return triageOfComplaint(user);
        if (system.equals(Prompts.EVENING_CARD)) return eveningCard(user);
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

    /**
     * Feature 25's turn without a model: the first three to five fact lines the server offered become the brief,
     * word for word, in both lists. Deterministic, and it is deliberately the <em>dullest</em> possible answer —
     * every number in it came from the facts, so the local H2 run and the API test exercise the accepting path of
     * the validator, while a test that wants the rejecting path mocks {@code LlmClient} and invents a number.
     *
     * <p>The Arabic list is the same lines with the school's own label in front, because the sample client has no
     * translator and a brief whose two lists disagreed in <em>length</em> would be rejected rather than merely
     * untranslated — which would make the fallback, not the accepting path, what the local run exercises.
     */
    private Result briefFromFacts(String user) {
        String block = between(user, "only numbers you may write):\n", "\n\nYour previous answer");
        List<String> facts = block.lines().map(String::strip).filter(l -> l.startsWith("- ")).map(l -> l.substring(2).strip())
                .filter(l -> !l.isEmpty()).limit(5).toList();
        if (facts.isEmpty()) facts = List.of("nothing needs her today");
        var out = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        var ar = out.putArray("linesAr");
        var en = out.putArray("linesEn");
        for (String fact : facts) { en.add(cut(fact, 110)); ar.add(cut("الموجز: " + fact, 110)); }
        while (en.size() < 3) { en.add("Nothing else needs you now."); ar.add("لا شيء آخر يحتاجك الآن"); }
        return new Result(out.toString(), 600, 300);
    }

    /**
     * Feature 26's turn without a model: the first line of the extracted text becomes the title, the whole text
     * becomes both summaries, every ISO date written in it becomes a key date, and every line that begins with a `-`
     * becomes an action. Deterministic, which is what lets the API test and the local H2 run upload a circular and
     * assert on what comes back — and it exercises the real checks either way, because the server still has to find
     * those dates parseable and the whole answer inside its limits.
     *
     * <p>An announcement is suggested only when the text says "parents", which is as close as a client with no model
     * can come to the prompt's own rule and is enough for the dashboard's "Draft announcement" to have something to
     * open with.
     */
    private Result summaryFromDocument(String user) {
        String fileName = between(user, "\nTHE FILE: ", "\n");
        String body = between(user, "headings are ours):\n", "\n\nYour previous answer").strip();
        List<String> lines = body.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("## Page")).toList();
        var out = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        out.put("title", cut(lines.isEmpty() ? fileName : lines.get(0), 120));
        out.put("summaryAr", cut(body, 1200));
        out.put("summaryEn", cut(body, 1200));
        var dates = out.putArray("keyDates");
        var day = ISO_DAY.matcher(body);
        while (day.find() && dates.size() < 10)
            dates.addObject().put("date", day.group()).put("labelAr", "موعد").put("labelEn", "A date in " + fileName);
        var ar = out.putArray("actionsAr"); var en = out.putArray("actionsEn");
        for (String line : lines) {
            if (!line.startsWith("-") || ar.size() >= 10) continue;
            String action = cut(line.substring(1).strip(), 200);
            ar.add(action); en.add(action);
        }
        if (body.toLowerCase().contains("parent") || body.contains("أولياء"))
            out.putObject("announcement").put("title", out.get("title").asText())
                    .put("bodyAr", cut(body, 1000)).put("bodyEn", cut(body, 1000));
        return new Result(out.toString(), 1100, 500);
    }

    /**
     * Feature 28's note without a model: the first block's own "HOW IT WENT" words in a fixed sentence pair that
     * keeps every rule the validator checks — `{child}` in both languages and not a digit anywhere. Deterministic,
     * which is what lets the API test read the card back and assert on it, and it still exercises the real checks.
     */
    private Result eveningCard(String user) {
        String en = "went well", ar = "سار على ما يرام";
        var went = WENT.matcher(user);
        if (went.find()) { en = went.group(1).strip(); ar = went.group(2).strip(); }
        var out = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        out.put("textEn", "{child} worked on today's homework — " + en + ". Ask about the part {child} practised this evening.");
        out.put("textAr", "عمل {child} في واجب اليوم — " + ar + ". اسألوا {child} هذا المساء عما تدرب عليه.");
        return new Result(out.toString(), 600, 300);
    }

    /** The result line of one `EveningCardFacts.Lesson.block`: `HOW IT WENT: went well / سار على ما يرام`. */
    private static final java.util.regex.Pattern WENT = java.util.regex.Pattern.compile("HOW IT WENT: ([^/\n]+) / ([^\n]+)");

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

    /**
     * Feature 27's turn without a model: the category is the first keyword the complaint carries, the urgency is
     * "high" when it says so and "normal" otherwise, the summary is the title and the first line of the body, and
     * the reply is one fixed courteous sentence. Deterministic, which is what lets `ComplaintTriageApiTest` and the
     * local H2 run open a complaint and assert on the chips — and the server still has to accept the shape, mask the
     * names before this is ever reached, and refuse an urgency it cannot draw.
     */
    private Result triageOfComplaint(String user) {
        String title = between(user, "THE COMPLAINT'S TITLE:\n", "\n\nWHAT THE PARENT WROTE");
        String body = between(user, "already masked):\n", "\n\nYour previous answer");
        String all = (title + " " + body).toLowerCase(java.util.Locale.ROOT);
        String category = "other";
        for (var guess : CATEGORY_WORDS) if (all.contains(guess[1])) { category = guess[0]; break; }
        String urgency = all.contains("urgent") || all.contains("unsafe") || all.contains("عاجل") ? "high"
                : all.contains("whenever") || all.contains("question") ? "low" : "normal";
        String summary = cut(title + " — " + body.lines().findFirst().orElse("").strip(), 400);
        var out = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        out.put("category", category); out.put("urgency", urgency);
        out.put("summaryAr", summary); out.put("summaryEn", summary);
        out.put("replyAr", "شكرًا لتواصلكم. سننظر في الأمر ونعود إليكم.");
        out.put("replyEn", "Thank you for telling us. We are looking into it and will come back to you.");
        return new Result(out.toString(), 500, 300);
    }

    /** `{category, the word that gives it away}`, in the order the sample tries them. */
    private static final String[][] CATEGORY_WORDS = {
            {"transport", "bus"}, {"fees", "fee"}, {"facilities", "toilet"}, {"behaviour", "bully"},
            {"staff", "teacher"}, {"academic", "homework"}};
}
