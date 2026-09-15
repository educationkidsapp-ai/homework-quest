package quest.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.analysis.AnalysisCacheRepository;
import quest.server.analysis.CacheEntities;
import quest.server.analysis.GenerationCacheRepository;
import quest.server.children.AttemptRepository;
import quest.server.children.Entities.AttemptEntity;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.PlayEntity;
import quest.server.content.Entities.StopEntity;
import quest.server.content.LessonRepository;
import quest.server.content.PlayRepository;
import quest.server.content.StopRepository;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TenantContext;

/**
 * P1.9: the two things `/admin/usage`, `/admin/calendar` and `/admin/cache` have to get right.
 *
 * <ul>
 *   <li><strong>No N+1.</strong> Each report reads a table once and groups in Java, so the statement count is the same
 *       for one lesson and for many — measured with Hibernate statistics, the way `TenantMapTest` measures the map.</li>
 *   <li><strong>Scope.</strong> Two schools share one source hash: each sees only its own lessons in the cache
 *       listing and only its own lessons in usage; the Admin without a school header keeps the global view.</li>
 * </ul>
 */
class ReportsQueryCountTest extends ApiTestSupport {
    private static final String A = "reports-school-a", B = "reports-school-b";
    private static final String SHARED_HASH = "reports-shared-source-hash";

    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired LessonRepository lessons;
    @Autowired PlayRepository plays;
    @Autowired StopRepository stops;
    @Autowired AttemptRepository attempts;
    @Autowired AnalysisCacheRepository analysisCache;
    @Autowired GenerationCacheRepository generationCache;
    @Autowired jakarta.persistence.EntityManagerFactory emf;

    private String childA, childB;

    @BeforeEach void seed() throws Exception {
        school(A, "Reports Academy", "REPAAA"); school(B, "Reports Beta", "REPBBB");
        klass(A); klass(B);
        childA = child("REPAAA"); childB = child("REPBBB");
        lesson("reports-a-1", A, 1, childA); lesson("reports-b-1", B, 2, childB);
        analysis(SHARED_HASH); generation(SHARED_HASH);
    }

    @Test void the_reports_cost_the_same_number_of_statements_whatever_the_number_of_lessons() throws Exception {
        String token = adminToken();
        long usageOne = statements(() -> mvc.perform(admin(get("/admin/usage"), token)).andExpect(status().isOk()));
        long calendarOne = statements(() -> mvc.perform(admin(get("/admin/calendar?curriculum=british&grade=1&year=2027&month=5"), token)).andExpect(status().isOk()));
        long cacheOne = statements(() -> mvc.perform(admin(get("/admin/cache"), token)).andExpect(status().isOk()));

        for (int i = 2; i <= 6; i++) lesson("reports-a-" + i, A, i, childA);
        analysis("reports-other-hash-1"); analysis("reports-other-hash-2");

        assertThat(statements(() -> mvc.perform(admin(get("/admin/usage"), token)).andExpect(status().isOk())))
                .as("/admin/usage must not run a query per lesson").isEqualTo(usageOne);
        assertThat(statements(() -> mvc.perform(admin(get("/admin/calendar?curriculum=british&grade=1&year=2027&month=5"), token)).andExpect(status().isOk())))
                .as("/admin/calendar must not run a query per day or lesson").isEqualTo(calendarOne);
        assertThat(statements(() -> mvc.perform(admin(get("/admin/cache"), token)).andExpect(status().isOk())))
                .as("/admin/cache must not run a query per cache entry").isEqualTo(cacheOne);
    }

    @Test void a_cache_entry_two_schools_share_is_listed_with_each_school_s_own_lessons() throws Exception {
        String token = adminToken();

        var global = json(mvc.perform(admin(get("/admin/cache"), token)).andExpect(status().isOk()).andReturn());
        var shared = entry(global, SHARED_HASH);
        assertThat(shared.get("lessonIds").toString()).contains("reports-a-1").contains("reports-b-1");
        assertThat(shared.get("tokenUsage").asLong()).as("the analysis and its generations").isEqualTo(300);

        var scopedToA = json(mvc.perform(admin(get("/admin/cache"), token).header(TenantContext.HEADER, A)).andExpect(status().isOk()).andReturn());
        var sharedForA = entry(scopedToA, SHARED_HASH);
        assertThat(sharedForA.get("lessonIds").toString()).contains("reports-a-1").doesNotContain("reports-b-1");
        assertThat(sharedForA.get("lessonIds").size()).isLessThan(shared.get("lessonIds").size());
        assertThat(sharedForA.get("hits").asInt()).as("never more than the real counter, never more than my own lessons")
                .isEqualTo(Math.min(shared.get("hits").asInt(), sharedForA.get("lessonIds").size()));

        analysis("reports-nobody-uses-this");
        var stillScoped = json(mvc.perform(admin(get("/admin/cache"), token).header(TenantContext.HEADER, A)).andExpect(status().isOk()).andReturn());
        assertThat(stillScoped.toString()).as("an entry no lesson of mine is built on is not my business").doesNotContain("reports-nobody-uses-this");
        assertThat(json(mvc.perform(admin(get("/admin/cache"), token)).andExpect(status().isOk()).andReturn()).toString())
                .as("the Admin without a school still sees everything").contains("reports-nobody-uses-this");
    }

    @Test void usage_is_scoped_to_the_school_header() throws Exception {
        String token = adminToken();
        assertThat(json(mvc.perform(admin(get("/admin/usage"), token)).andExpect(status().isOk()).andReturn()).toString())
                .contains("reports-a-1").contains("reports-b-1");
        assertThat(json(mvc.perform(admin(get("/admin/usage"), token).header(TenantContext.HEADER, A)).andExpect(status().isOk()).andReturn()).toString())
                .contains("reports-a-1").doesNotContain("reports-b-1");
        assertThat(json(mvc.perform(admin(get("/admin/calendar?curriculum=british&grade=1&year=2027&month=5"), token).header(TenantContext.HEADER, B)).andExpect(status().isOk()).andReturn()).toString())
                .as("school B has no lesson on A's day").doesNotContain("2027-05-01");
    }

    // ---------------------------------------------------------------- helpers

    private interface Call { void run() throws Exception; }

    /** Statements the call costs, measured on the Hibernate session factory (as `TenantMapTest` does for the map). */
    private long statements(Call call) throws Exception {
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true); stats.clear();
        call.run();
        return stats.getPrepareStatementCount();
    }

    private com.fasterxml.jackson.databind.JsonNode entry(com.fasterxml.jackson.databind.JsonNode listing, String hash) {
        for (var e : listing) if (hash.equals(e.get("fileHash").asText())) return e;
        throw new AssertionError(hash + " is not in " + listing);
    }

    /** A real child (and parent) so the attempts below satisfy their foreign key. */
    private String child(String schoolCode) throws Exception {
        return parentPost("/children", "{\"name\":\"Rana\",\"avatarColor\":\"sun\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"" + schoolCode + "\"}").get("id").asText();
    }

    private void school(String id, String name, String code) {
        if (schools.existsById(id)) return;
        var s = new Entities.SchoolEntity();
        s.setId(id); s.setName(name); s.setCode(code); s.setCurriculumOptionsJson("[\"british\"]"); s.setGradeOptionsJson("[1,2,3]"); s.setCreatedAt(Instant.now());
        schools.save(s);
    }

    private void klass(String schoolId) {
        String id = schoolId + ":british:1:math";
        if (classes.existsById(id)) return;
        var k = new Entities.ClassEntity();
        k.setId(id); k.setSchoolId(schoolId); k.setCurriculum("british"); k.setGrade(1); k.setSubject("math"); k.setCreatedAt(Instant.now());
        classes.save(k);
    }

    /** A published lesson with one play, one stop and one attempt, so every branch of `/admin/usage` has work to do. */
    private void lesson(String id, String schoolId, int day, String childId) {
        if (lessons.existsById(id)) return;
        var l = new LessonEntity();
        l.setId(id); l.setSchoolId(schoolId); l.setClassId(schoolId + ":british:1:math"); l.setCourseId("british/1"); l.setSubject("math");
        l.setDate(LocalDate.of(2027, 5, day)); l.setStatus("published"); l.setVersion(1); l.setTitle("Lesson " + id); l.setSource("pdf");
        l.setSourceHash(SHARED_HASH);
        l.setCreatedAt(Instant.now()); l.setUpdatedAt(Instant.now()); l.setPublishedAt(Instant.now());
        lessons.save(l);

        var p = new PlayEntity();
        p.setId(id + ":1:0"); p.setLessonId(id); p.setLevel(1); p.setVariant(0); p.setPlayJson("{}"); p.setPromptVersion("v1"); p.setSeed(1); p.setGeneratedAt(Instant.now());
        plays.save(p);
        var s = new StopEntity();
        s.setId(id + ":stop-1"); s.setPlayId(p.getId()); s.setLessonId(id); s.setPosition(1); s.setType("mcq"); s.setCategory("exit");
        s.setTitle("Stop"); s.setIngredient("count"); s.setContentJson("{}"); s.setParentTipEn(""); s.setParentTipAr("");
        stops.save(s);
        var a = new AttemptEntity();
        a.setId(UUID.randomUUID().toString()); a.setChildId(childId); a.setStopId(s.getId()); a.setLessonId(id); a.setLevel(1);
        a.setAnswerJson("{}"); a.setCorrect(true); a.setAttemptNumber(1); a.setAnsweredAt(Instant.now());
        attempts.save(a);
    }

    private void analysis(String hash) {
        String key = hash + ":v1";
        if (analysisCache.existsById(key)) return;
        var e = new CacheEntities.AnalysisCacheEntity();
        e.setCacheKey(key); e.setSourceHash(hash); e.setCurriculum("british"); e.setGrade(1); e.setSubject("math");
        e.setPromptVersion("v1"); e.setAnalysisJson("{}"); e.setTokenUsage(100); e.setHits(3); e.setCreatedAt(Instant.now());
        analysisCache.save(e);
    }

    private void generation(String hash) {
        String key = hash + ":play:v1";
        if (generationCache.existsById(key)) return;
        var e = new CacheEntities.GenerationCacheEntity();
        e.setCacheKey(key); e.setSourceHash(hash); e.setKind("play"); e.setPromptVersion("v1"); e.setJson("{}");
        e.setTokenUsage(200); e.setHits(1); e.setCreatedAt(Instant.now());
        generationCache.save(e);
    }
}
