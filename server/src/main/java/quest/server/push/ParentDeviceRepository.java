package quest.server.push;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import quest.server.push.Entities.ParentDeviceEntity;

/** V32. Every query names the parent or the token; `parent_devices` is not a tenant table (see the entity). */
@Transactional(readOnly = true)
public interface ParentDeviceRepository extends JpaRepository<ParentDeviceEntity, String> {
    Optional<ParentDeviceEntity> findByToken(String token);

    /** Her phones, the one seen most recently first — what a push goes to, and what the cap keeps. */
    List<ParentDeviceEntity> findByParentIdOrderByLastSeenAtDescIdAsc(String parentId);

    /** Every phone of a fan-out's parents in one statement — what one broadcast or one release pushes to. */
    List<ParentDeviceEntity> findByParentIdIn(java.util.Collection<String> parentIds);

    @Transactional @Modifying
    @Query("delete from ParentDeviceEntity d where d.token = :token and d.parentId = :parentId")
    int deleteOwned(@Param("token") String token, @Param("parentId") String parentId);

    /** FCM said the token is dead: whoever holds it, it goes. */
    @Transactional @Modifying
    @Query("delete from ParentDeviceEntity d where d.token = :token")
    int deleteByTokenValue(@Param("token") String token);
}
