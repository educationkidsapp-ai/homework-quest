package quest.server.translate;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import quest.server.translate.Entities.TranslationEntity;

/**
 * The cache. {@code @Transactional} for {@link quest.server.ai.AiCallRepository}'s reason: the `school` filter is
 * enabled per transaction, and a query that opened its own would run with it off.
 *
 * <p>{@link #hit} names the school explicitly <em>as well</em> as being filtered, because a parent carries no tenant
 * scope at all — `BroadcastRepository.live`'s rule, and the school it is given is the source row's own, resolved
 * before this is called and never a request parameter.
 */
@Transactional
public interface TranslationRepository extends JpaRepository<TranslationEntity, String> {
    @Query("""
            select t from TranslationEntity t
            where t.schoolId = :schoolId and t.sourceKind = :kind and t.sourceId = :sourceId
              and t.lang = :lang and t.sourceHash = :hash
            """)
    Optional<TranslationEntity> hit(@Param("schoolId") String schoolId, @Param("kind") String kind,
                                    @Param("sourceId") String sourceId, @Param("lang") String lang,
                                    @Param("hash") String hash);

    /** Every translation of one source, for the tests and for a sweep of a source that is gone. */
    @Query("select t from TranslationEntity t where t.sourceKind = :kind and t.sourceId = :sourceId")
    List<TranslationEntity> of(@Param("kind") String kind, @Param("sourceId") String sourceId);
}
