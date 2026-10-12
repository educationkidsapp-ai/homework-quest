package quest.server.push;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import quest.server.push.Entities.StaffDeviceEntity;

/**
 * V36. Every query names the member of staff or the token, never a school from a request;
 * `staff_devices` is a tenant table, so this interface is {@code @Transactional} for the reason every repository of a
 * filtered entity is — Spring Data makes only the `CrudRepository` methods transactional, and a derived query outside
 * a transaction never reaches {@link quest.server.tenancy.TenantTransactionManager}, where the `school` filter is
 * switched on. `TenantArchitectureTest` fails if this annotation is lost.
 */
@Transactional(readOnly = true)
public interface StaffDeviceRepository extends JpaRepository<StaffDeviceEntity, String> {
    Optional<StaffDeviceEntity> findByToken(String token);

    /** Her phones, the one seen most recently first — what a push goes to, and what the cap keeps. */
    List<StaffDeviceEntity> findByUserIdOrderByLastSeenAtDescIdAsc(String userId);

    /** Every phone of a fan-out's recipients in one statement. */
    List<StaffDeviceEntity> findByUserIdIn(Collection<String> userIds);

    @Transactional @Modifying
    @Query("delete from StaffDeviceEntity d where d.token = :token and d.userId = :userId")
    int deleteOwned(@Param("token") String token, @Param("userId") String userId);

    /** FCM said the token is dead: whoever holds it, it goes. */
    @Transactional @Modifying
    @Query("delete from StaffDeviceEntity d where d.token = :token")
    int deleteByTokenValue(@Param("token") String token);
}
