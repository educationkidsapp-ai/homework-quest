package quest.server.classes;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.UserRepository;
import quest.server.children.ChildRepository;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TeachingAssignmentRepository;
import quest.server.tenancy.TenantContext;

/**
 * The fixture the N1.1 tests share: one school with nothing in it, so each test builds the sections, teachers and
 * children it is about through the real Admin API rather than by writing rows. Everything a test seeds is prefixed
 * with its own key and {@link #removeSeed} takes it out again — the suite shares one H2 database.
 */
abstract class ClassesTestSupport extends ApiTestSupport {
    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired TeachingAssignmentRepository assignments;
    @Autowired ChildRepository childRows;
    @Autowired UserRepository users;
    @Autowired AdminJwtService jwt;
    @Autowired jakarta.persistence.EntityManagerFactory emf;

    /** The prefix every row this test seeds carries, so {@link #removeSeed} can find it again. */
    abstract String prefix();

    SchoolEntity school(String id, String name, String code) {
        return schools.findById(id).orElseGet(() -> {
            var s = new SchoolEntity();
            s.setId(id); s.setName(name); s.setCode(code);
            s.setCurriculumOptionsJson("[\"british\",\"american\"]"); s.setGradeOptionsJson("[1,2,3]");
            s.setStatus("active"); s.setCreatedAt(Instant.now());
            return schools.save(s);
        });
    }

    UserEntity user(String id, String schoolId, String email, String role) {
        return users.findById(id).orElseGet(() -> {
            var u = new UserEntity();
            u.setId(id); u.setSchoolId(schoolId); u.setEmail(email); u.setPasswordHash("x"); u.setRole(role);
            u.setStatus("active"); u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
            return users.save(u);
        });
    }

    String token(String userId, String role, String schoolId) { return jwt.issue(userId, userId + "@seed.test", role, schoolId).token(); }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder, String token, String schoolId) {
        var request = as(builder, token);
        return schoolId == null ? request : request.header(TenantContext.HEADER, schoolId);
    }

    /** The statements one request costs, measured with Hibernate's own statistics. */
    long statements(Runnable request) {
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true); stats.clear();
        request.run();
        return stats.getPrepareStatementCount();
    }

    /**
     * Puts back what this test seeded, so the next `@BeforeEach` can create "1A" again rather than hit its own 409:
     * children first (they point at a class), then the assignments, then the sections themselves.
     */
    void removeSeed() {
        String p = prefix();
        childRows.deleteAll(childRows.findAll().stream().filter(c -> c.getSchoolId().startsWith(p)).toList());
        assignments.deleteAll(assignments.findAll().stream().filter(a -> a.getSchoolId().startsWith(p)).toList());
        classes.deleteAll(classes.findAll().stream().filter(k -> k.getSchoolId().startsWith(p)).toList());
    }
}
