package quest.server.ai;

import java.util.List;
import quest.api.samples.Samples;

/**
 * Answers from the shared sample outputs so the whole server runs without an API key
 * (`FAKE_LLM=true`, and in tests). Picks the sample by subject / skill id mentioned in the user turn.
 */
public class SampleLlmClient implements LlmClient {
    @Override
    public String complete(String system, List<Turn> turns) {
        String user = turns.stream().filter(t -> t.role().equals("user")).flatMap(t -> t.blocks().stream())
                .filter(b -> b instanceof Block.Text).map(b -> ((Block.Text) b).text()).reduce("", (a, b) -> a + "\n" + b);
        Samples s = Samples.INSTANCE;
        if (user.contains("List the skills taught")) {
            return user.contains("Subject: english") ? s.getSkillExtractionEnglish() : s.getSkillExtractionMath();
        }
        String skillId = user.lines().filter(l -> l.startsWith("Skill id: ")).map(l -> l.substring(10).trim()).findFirst().orElse("");
        String mode = user.lines().filter(l -> l.startsWith("Mode: ")).map(l -> l.substring(6).trim()).findFirst().orElse("normal");
        String sample;
        if (skillId.endsWith("sh-sound")) sample = s.getQuestionSetShSound();
        else if (skillId.endsWith("sight-words-week-3")) sample = s.getQuestionSetSightWords();
        else sample = s.getQuestionSetCountingBy2s();
        // Re-key so ids are unique per skill and per mode (the real model does this itself).
        String prefix = Integer.toHexString(Math.abs((skillId + mode + user.hashCode()).hashCode()));
        return sample.replace("\"skillId\": \"" + sample.substring(sample.indexOf("\"skillId\": \"") + 12, sample.indexOf("\"", sample.indexOf("\"skillId\": \"") + 12)) + "\"", "\"skillId\": \"" + skillId + "\"")
                .replace("\"mode\": \"normal\"", "\"mode\": \"" + mode + "\"")
                .replaceAll("\"id\": \"([a-z0-9]+-q\\d)\"", "\"id\": \"" + prefix + "-$1\"");
    }
}
