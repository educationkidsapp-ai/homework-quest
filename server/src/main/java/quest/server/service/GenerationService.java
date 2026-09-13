package quest.server.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import quest.api.validation.SchemaValidator;
import quest.api.validation.Schemas;
import quest.api.validation.ValidationResult;
import quest.server.ai.LlmClient;
import quest.server.ai.Prompts;
import quest.server.api.dto.Enums;
import quest.server.api.dto.Questions;
import quest.server.config.Json;
import quest.server.domain.SkillEntity;
import quest.server.repository.*;

/** Prompt B: one confirmed skill → one validated question set, cached by (skill, mode, seed). */
@Service
public class GenerationService {
    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);
    private final LlmClient llm;
    private final Json json;
    private final QuestionSetRepository sets;
    private final QuestionSetStore store;
    private final String systemB = Prompts.systemB(Schemas.INSTANCE.getQuestionSet());

    public GenerationService(LlmClient llm, Json json, QuestionSetRepository sets, QuestionSetStore store) {
        this.llm = llm; this.json = json; this.sets = sets; this.store = store;
    }

    public Questions.QuestionSet generate(SkillEntity skill, int grade, Enums.Mode mode, List<String> excludedIds, int length) throws GenerationException {
        // Cache key: what the client asked for. Server-known ids are still excluded from the new set.
        String seed = seed(new HashSet<>(excludedIds), length);
        Set<String> excluded = new HashSet<>(excludedIds);
        excluded.addAll(store.shownIds(skill.getId()));
        var cached = sets.findBySkillIdAndModeAndSeed(skill.getId(), mode.wire(), seed);
        if (cached.isPresent()) {
            log.info("generate cache hit skill={} mode={}", skill.getId(), mode.wire());
            return store.load(cached.get());
        }
        String user = Prompts.userB(skill.getId(), skill.getName(), skill.getSubject(), skill.getMethod(), json.strings(skill.getExamplesJson()),
                grade, mode.wire(), length, excluded.stream().sorted().toList());
        List<LlmClient.Turn> turns = new ArrayList<>();
        turns.add(new LlmClient.Turn("user", List.of(new LlmClient.Block.Text(user))));
        ValidationResult last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String raw;
            try { raw = llm.complete(systemB, turns); } catch (LlmClient.LlmException e) { throw new GenerationException(e.getMessage(), e); }
            String cleaned = JsonText.stripFences(raw);
            last = SchemaValidator.INSTANCE.validateQuestionSetJson(cleaned, length, excluded);
            if (last.isValid()) {
                Questions.QuestionSet parsed = json.read(cleaned, Questions.QuestionSet.class).withSkillId(skill.getId());
                if (parsed.mode() != mode) parsed = new Questions.QuestionSet(null, skill.getId(), mode, parsed.explanation(), parsed.workedExamples(), parsed.questions());
                log.info("generate ok skill={} mode={} attempt={}", skill.getId(), mode.wire(), attempt + 1);
                return store.save(parsed, seed);
            }
            log.warn("generate invalid skill={} attempt={} errors={}", skill.getId(), attempt + 1, last.getErrors());
            turns.add(new LlmClient.Turn("assistant", List.of(new LlmClient.Block.Text(raw))));
            turns.add(new LlmClient.Turn("user", List.of(new LlmClient.Block.Text(Prompts.retry(last.getErrors())))));
        }
        throw new GenerationException("The questions did not pass validation: " + String.join("; ", last.getErrors().subList(0, Math.min(3, last.getErrors().size()))), null);
    }

    static String seed(Set<String> excluded, int length) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            List<String> sorted = new ArrayList<>(excluded);
            sorted.sort(String::compareTo);
            md.update((length + "|" + String.join(",", sorted)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static class GenerationException extends Exception {
        public GenerationException(String message, Throwable cause) { super(message, cause); }
    }
}
