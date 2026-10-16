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

    /**
     * The sweep V51 promised, for the one case that has to happen at once: a hard-deleted child
     * ({@link quest.server.classes.RosterService#delete}). Her family's messages are deleted there, and the full
     * text of them in another language has no foreign key to go with it — so it is named here, by the message ids
     * that are about to stop existing and by the school those messages were in.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from TranslationEntity t where t.schoolId = :schoolId and t.sourceKind in ('chat', 'complaint')"
            + " and t.sourceId in :sourceIds")
    int deleteBySources(@Param("schoolId") String schoolId, @Param("sourceIds") java.util.Collection<String> sourceIds);
}
