package quest.server.tenancy;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;

/**
 * The Hibernate filter that scopes every tenant table to one school, and the only place that switches it on.
 * `@FilterDef` lives on {@link Entities.ClassEntity} (filter definitions are global to the session factory);
 * `@Filter(name = "school")` sits on every entity with a `school_id` column.
 * `TenantArchitectureTest` fails the build if anything outside `quest.server.tenancy` calls `enableFilter`.
 */
public final class TenantFilter {
    public static final String NAME = "school";
    public static final String PARAM = "schoolId";
    public static final String CONDITION = "school_id = :schoolId";

    private TenantFilter() {}

    /** Enables the filter on the session behind {@code em}; calling it twice on one session only re-sets the parameter. */
    public static void enable(EntityManager em, String schoolId) {
        em.unwrap(Session.class).enableFilter(NAME).setParameter(PARAM, schoolId).validate();
    }
}
