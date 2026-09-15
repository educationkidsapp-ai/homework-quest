package quest.server.tenancy;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Enables the `school` Hibernate filter on the session of every transaction opened while a request is scoped to a
 * school. This is where the filter has to be switched on, and not anywhere else:
 *
 * <ul>
 *   <li>`spring.jpa.open-in-view` is false, so there is no request-wide {@code EntityManager} a
 *       {@code HandlerInterceptor} could enable the filter on — a session opened in an interceptor is closed again
 *       before the first repository call and the filter goes with it.</li>
 *   <li>A Spring Data repository method called outside a service transaction opens its own transaction
 *       (`SimpleJpaRepository` is `@Transactional(readOnly = true)`), so `AdminReportsController`-style code that
 *       calls repositories directly would never see a filter enabled by an aspect around `@Transactional` services.</li>
 *   <li>An AOP aspect around repositories or services cannot guarantee it runs <em>inside</em> the transaction:
 *       {@code TransactionInterceptor} runs at `Ordered.LOWEST_PRECEDENCE`, so an aspect either runs before the
 *       transaction starts (no session yet) or ties with it unpredictably.</li>
 * </ul>
 *
 * Overriding {@code doBegin} catches every one of them — service transactions, the implicit per-repository-method
 * transactions, and `REQUIRES_NEW` — because Spring calls it exactly once per physical transaction, right after the
 * {@code EntityManager} is created and bound to the thread. A transaction that joins an existing one inherits the
 * filter that was enabled when the outer one began.
 *
 * <p>One gap it cannot close on its own: Spring Data makes only the `CrudRepository` methods transactional, so a
 * <em>derived</em> or {@code @Query} method called outside a service transaction opens a session without ever asking
 * a transaction manager. The three repositories of tenant entities (`LessonRepository`, `ChildRepository`,
 * `ClassRepository`) therefore carry `@Transactional(readOnly = true)` on the interface, which brings those methods
 * here too; `TenantArchitectureTest` fails if one of them loses it.
 *
 * <p>The scope is resolved <em>before</em> {@code super.doBegin}: an unknown `X-School-Id` throws 404 before a
 * transaction exists, so nothing has to be unwound. {@link TenantContext#schoolId()} is re-entrancy safe — the
 * `schools` lookup it performs while resolving opens a transaction of its own, which finds the scope unresolved and
 * simply runs unfiltered (`schools` is not a tenant table).
 *
 * <p>ADMIN without `X-School-Id` has a null scope and no filter: it reads across schools (D6).
 */
public class TenantTransactionManager extends JpaTransactionManager {
    private final transient TenantContext tenant;

    public TenantTransactionManager(EntityManagerFactory emf, TenantContext tenant) { super(emf); this.tenant = tenant; }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        String schoolId = tenant.schoolId();
        super.doBegin(transaction, definition);
        if (schoolId == null) return;
        var holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(getEntityManagerFactory());
        if (holder != null && holder.getEntityManager() != null) TenantFilter.enable(holder.getEntityManager(), schoolId);
    }
}
