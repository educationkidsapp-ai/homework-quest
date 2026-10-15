package quest.server.children;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature 28's cards. Every method is transactional so the `school` filter is on for all of them, as
 * {@link ChildRepository} explains, and every query names `school_id` as well: a parent's request carries no tenant
 * scope, and the 18:00 job has no request behind it at all.
 */
@Transactional(readOnly = true)
public interface EveningCardRepository extends JpaRepository<Entities.EveningCardEntity, String> {
    @Query("select c from EveningCardEntity c where c.schoolId = :schoolId and c.childId = :childId and c.cardDay = :day")
    Optional<Entities.EveningCardEntity> findOneOfChild(@Param("schoolId") String schoolId, @Param("childId") String childId, @Param("day") LocalDate day);

    /**
     * The claim: written only if V50's unique `evening_cards_once` has no row for this child and day yet, so two
     * instances generating at the same moment cannot both store a card. 1 if written, 0 if one was there already —
     * and the caller then reads that one, which is what makes a second request answer the same words.
     */
    @Modifying @Transactional
    @Query(nativeQuery = true, value = "insert into evening_cards (id, school_id, child_id, card_day, facts_json, text_ar, text_en, source, created_at)"
            + " values (:#{#c.id}, :#{#c.schoolId}, :#{#c.childId}, :#{#c.cardDay}, :#{#c.factsJson}, :#{#c.textAr}, :#{#c.textEn}, :#{#c.source}, :#{#c.createdAt})"
            + " on conflict do nothing")
    int insertOnce(@Param("c") Entities.EveningCardEntity c);
}
