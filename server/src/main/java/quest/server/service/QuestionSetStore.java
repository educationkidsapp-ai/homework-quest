package quest.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.api.dto.Enums;
import quest.server.api.dto.Questions;
import quest.server.config.Json;
import quest.server.domain.QuestionEntity;
import quest.server.domain.QuestionSetEntity;
import quest.server.repository.*;

/** Persists question sets in the §5 shape (prompt/options/hint/numberLine/illustration columns). */
@Service
public class QuestionSetStore {
    private final QuestionSetRepository sets;
    private final QuestionRepository questions;
    private final Json json;

    public QuestionSetStore(QuestionSetRepository sets, QuestionRepository questions, Json json) {
        this.sets = sets; this.questions = questions; this.json = json;
    }

    @Transactional
    public Questions.QuestionSet save(Questions.QuestionSet set, String seed) {
        String id = UUID.randomUUID().toString();
        QuestionSetEntity e = new QuestionSetEntity();
        e.setId(id); e.setSkillId(set.skillId()); e.setMode(set.mode().wire()); e.setSeed(seed);
        e.setExplanation(set.explanation()); e.setWorkedExamplesJson(json.write(set.workedExamples())); e.setGeneratedAt(Instant.now());
        sets.save(e);
        // Models number questions q1..qN in every set; make ids globally unique so sets never overwrite each other
        // (the app's local table and the no-repeat rule key on this id too).
        String prefix = id.substring(0, 8);
        List<Questions.Question> renamed = set.questions().stream().map(q -> q.withId(prefix + "-" + q.id())).toList();
        int pos = 0;
        for (Questions.Question q : renamed) {
            QuestionEntity qe = new QuestionEntity();
            qe.setId(q.id()); qe.setQuestionSetId(id); qe.setPosition(pos++); qe.setType(q.typeName());
            qe.setPromptJson(json.write(q)); qe.setOptionsJson(json.write(q.optionIds())); qe.setCorrectOptionId(q.correctOptionId());
            qe.setHint(q.hint()); qe.setNumberLineJson(q.numberLine() == null ? null : json.write(q.numberLine())); qe.setIllustrationKey(q.illustrationKey());
            questions.save(qe);
        }
        return new Questions.QuestionSet(id, set.skillId(), set.mode(), set.explanation(), set.workedExamples(), renamed);
    }

    @Transactional(readOnly = true)
    public Questions.QuestionSet load(QuestionSetEntity e) {
        List<Questions.Question> qs = new ArrayList<>();
        for (QuestionEntity qe : questions.findByQuestionSetIdOrderByPosition(e.getId())) qs.add(json.read(qe.getPromptJson(), Questions.Question.class));
        return new Questions.QuestionSet(e.getId(), e.getSkillId(), Enums.Mode.from(e.getMode()), e.getExplanation(),
                json.read(e.getWorkedExamplesJson(), new TypeReference<List<Questions.WorkedExample>>() {}), qs);
    }

    @Transactional(readOnly = true)
    public List<Questions.QuestionSet> forSkill(String skillId) {
        return sets.findBySkillIdOrderByGeneratedAt(skillId).stream().map(this::load).toList();
    }

    @Transactional(readOnly = true)
    public List<String> shownIds(String skillId) {
        List<String> ids = new ArrayList<>();
        for (QuestionSetEntity e : sets.findBySkillIdOrderByGeneratedAt(skillId))
            for (QuestionEntity q : questions.findByQuestionSetIdOrderByPosition(e.getId())) ids.add(q.getId());
        return ids;
    }
}
