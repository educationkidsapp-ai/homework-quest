package quest.server.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import quest.server.domain.QuestionSetEntity;

public interface QuestionSetRepository extends JpaRepository<QuestionSetEntity, String> {
    List<QuestionSetEntity> findBySkillIdOrderByGeneratedAt(String skillId);
    Optional<QuestionSetEntity> findBySkillIdAndModeAndSeed(String skillId, String mode, String seed);
}
