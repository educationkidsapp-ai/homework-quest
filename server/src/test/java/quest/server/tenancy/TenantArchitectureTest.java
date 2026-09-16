package quest.server.tenancy;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import java.lang.reflect.ParameterizedType;
import java.util.Set;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** The rules that keep the tenant filter from being forgotten (§2: isolation is enforced in the server, not the UI). */
class TenantArchitectureTest {
    private static final JavaClasses SERVER = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("quest.server");

    /**
     * `invites` and `audit_log` also carry a `school_id` and are still unfiltered: both are written and read by flows
     * that legitimately run outside a school scope — an invitation is opened and accepted by someone with no token at
     * all, and the platform ADMIN's audit trail spans every school. `users` left this list in P1.3.
     *
     * <p>P2.1 adds the two flag tables for the same reason: `GET /schools/{id}/flags` is public (the app reads it
     * before anyone signs in), a parent's flags are resolved from her child's school with no dashboard scope at all,
     * and the Admin matrix spans every school. Every read of them names its `school_id` explicitly and
     * `FlagService.matrix` scopes a MANAGERIAL caller to her own school.
     * Remove an entry as soon as its entity is annotated.
     */
    private static final Set<String> PENDING = Set.of(
            "quest.server.auth.Entities$InviteEntity", "quest.server.auth.Entities$AuditLogEntity",
            "quest.server.flags.Entities$SchoolFeatureFlagEntity", "quest.server.flags.Entities$FlagAuditEntity");

    @Test void every_tenant_entity_carries_the_school_filter() {
        classes().that().areAnnotatedWith(Entity.class).and(new com.tngtech.archunit.base.DescribedPredicate<JavaClass>("have a schoolId field") {
                    @Override public boolean test(JavaClass c) { return hasSchoolId(c) && !PENDING.contains(c.getName()); }
                })
                .should(new ArchCondition<JavaClass>("be annotated with @Filter(\"school\")") {
                    @Override public void check(JavaClass c, ConditionEvents events) {
                        var filter = c.tryGetAnnotationOfType(Filter.class);
                        boolean ok = filter.isPresent() && TenantFilter.NAME.equals(filter.get().name()) && filter.get().condition().contains("school_id");
                        events.add(new SimpleConditionEvent(c, ok, c.getName() + (ok ? " is filtered" : " has a schoolId column but no @Filter(\"school\")")));
                    }
                })
                .because("a tenant table that is not filtered is readable across schools")
                .check(SERVER);
    }

    @Test void only_the_tenancy_package_enables_the_filter() {
        noClasses().that().resideOutsideOfPackage("quest.server.tenancy..")
                .should().callMethodWhere(target(name("enableFilter")))
                .because("the school filter is switched on in one place (TenantFilter), from the transaction manager")
                .check(SERVER);
    }

    @Test void every_repository_of_a_tenant_entity_is_transactional() {
        classes().that().areAssignableTo(JpaRepository.class).and(new com.tngtech.archunit.base.DescribedPredicate<JavaClass>("manage a filtered entity") {
                    @Override public boolean test(JavaClass c) { var e = entityOf(c); return c.isInterface() && e != null && e.isAnnotationPresent(Filter.class); }
                })
                .should(new ArchCondition<JavaClass>("be annotated with @Transactional") {
                    @Override public void check(JavaClass c, ConditionEvents events) {
                        boolean ok = c.isAnnotatedWith(Transactional.class);
                        events.add(new SimpleConditionEvent(c, ok, c.getName() + (ok ? " is transactional" : " has derived queries that would run outside a transaction, where the filter is off")));
                    }
                })
                .because("Spring Data only makes the CrudRepository methods transactional; the filter is enabled per transaction")
                .check(SERVER);
    }

    private static boolean hasSchoolId(JavaClass c) { return c.getAllFields().stream().anyMatch(f -> f.getName().equals("schoolId")); }

    /** The entity a repository interface is declared for, or null. */
    private static Class<?> entityOf(JavaClass repository) {
        for (var type : repository.reflect().getGenericInterfaces())
            if (type instanceof ParameterizedType p && JpaRepository.class.equals(p.getRawType()) && p.getActualTypeArguments()[0] instanceof Class<?> entity) return entity;
        return null;
    }
}
