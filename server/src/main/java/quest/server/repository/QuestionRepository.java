package quest.server.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import quest.server.domain.QuestionEntity;

public interface QuestionRepository extends JpaRepository<QuestionEntity, String> {
    List<QuestionEntity> findByQuestionSetIdOrderByPosition(String questionSetId);
}
