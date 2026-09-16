package quest.server.teacher;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Transactional for the same reason as every other tenant repository (see {@link TeacherQuestionRepository}). */
@Transactional(readOnly = true)
public interface TeacherQuestionAnswerRepository extends JpaRepository<Entities.TeacherQuestionAnswerEntity, String> {
    List<Entities.TeacherQuestionAnswerEntity> findByQuestionIdOrderByAnsweredAtAsc(String questionId);
    List<Entities.TeacherQuestionAnswerEntity> findByQuestionIdAndChildId(String questionId, String childId);
    List<Entities.TeacherQuestionAnswerEntity> findByChildIdOrderByAnsweredAtDesc(String childId);

    /** Every answer of a page of questions in one statement, so the results summary costs a fixed number of queries. */
    @Query("select a from TeacherQuestionAnswerEntity a where a.questionId in :questionIds")
    List<Entities.TeacherQuestionAnswerEntity> findByQuestionIdIn(@Param("questionIds") Collection<String> questionIds);

    /** How many stops of each question this child has answered — the `answered` count on a teacher island. */
    @Query("select a.questionId, count(a) from TeacherQuestionAnswerEntity a"
            + " where a.childId = :childId and a.questionId in :questionIds group by a.questionId")
    List<Object[]> countAnsweredByQuestion(@Param("childId") String childId, @Param("questionIds") Collection<String> questionIds);

    @Query("select a from TeacherQuestionAnswerEntity a where a.questionId = :questionId and a.childId = :childId and a.stopId = :stopId")
    Optional<Entities.TeacherQuestionAnswerEntity> findOne(@Param("questionId") String questionId, @Param("childId") String childId, @Param("stopId") String stopId);
}
