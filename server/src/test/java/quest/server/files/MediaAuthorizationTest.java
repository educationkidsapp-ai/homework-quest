package quest.server.files;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.UserRepository;
import quest.server.children.ChildMediaRepository;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.PageImageEntity;
import quest.server.content.LessonRepository;
import quest.server.content.PageImageRepository;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TenantContext;

/**
 * P1.9: `/media/**` is authenticated and scoped by school. The page ids are guessable (`<lesson prefix>:page-N`), so
 * the assertions below are the whole protection: a parent reaches the published lessons of her children's schools and
 * nothing else, a dashboard user reaches her scope, and a refusal is always 404 — never 403, which would confirm that
 * the id exists.
 */
class MediaAuthorizationTest extends ApiTestSupport {
    private static final String A = "media-school-a", B = "media-school-b";
    private static final String PAGE_A = "mediaaaa:page-1", PAGE_B = "mediabbb:page-1", PAGE_DRAFT = "mediadrf:page-1";

    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired LessonRepository lessons;
    @Autowired PageImageRepository pageImages;
    @Autowired ChildMediaRepository childMedia;
    @Autowired UserRepository users;
    @Autowired AdminJwtService jwt;
    @Autowired FileStore files;

    @BeforeEach void seed() {
        school(A, "Media Academy", "MEDAAA"); school(B, "Other Media School", "MEDBBB");
        klass(A); klass(B);
        lesson("media-lesson-a", A, "published"); lesson("media-lesson-b", B, "published"); lesson("media-lesson-draft", A, "review");
        page(PAGE_A, "media-lesson-a"); page(PAGE_B, "media-lesson-b"); page(PAGE_DRAFT, "media-lesson-draft");
        user("media-teacher-a", A, "media-teacher@alpha.test"); user("media-teacher-b", B, "media-teacher@beta.test");
    }

    /**
     * The whole suite shares one H2 database, and `TenancyContractTest` asserts that <em>every</em> lesson in it lives
     * in the default school — so a test that seeds lessons of its own outside that school puts them back.
     */
    @org.junit.jupiter.api.AfterEach void removeWhatThisTestSeeded() {
        pageImages.deleteAll(pageImages.findAll().stream().filter(p -> p.getLessonId().startsWith("media-lesson-")).toList());
        childMedia.deleteAll(childMedia.findAll().stream().filter(m -> m.getId().startsWith("media-rec-")).toList());
        lessons.deleteAll(lessons.findAll().stream().filter(l -> l.getId().startsWith("media-lesson-")).toList());
    }

    @Test void a_parent_reads_her_own_school_and_nothing_else() throws Exception {
        parentPost("/children", "{\"name\":\"Lina\",\"avatarColor\":\"mint\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"MEDAAA\"}");

        mvc.perform(page(PAGE_A).header("Authorization", PARENT)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")));
        mvc.perform(page(PAGE_B).header("Authorization", PARENT)).andExpect(status().isNotFound());
        mvc.perform(page(PAGE_DRAFT).header("Authorization", PARENT)).andExpect(status().isNotFound());
    }

    @Test void a_parent_with_no_child_in_the_school_reads_nothing() throws Exception {
        mvc.perform(page(PAGE_A).header("Authorization", PARENT)).andExpect(status().isNotFound());
    }

    @Test void a_teacher_reads_her_own_school_and_nothing_else() throws Exception {
        String teacherA = jwt.issue("media-teacher-a", "media-teacher@alpha.test", "TEACHER", A).token();
        mvc.perform(page(PAGE_A).header("Authorization", "Bearer " + teacherA)).andExpect(status().isOk());
        mvc.perform(page(PAGE_B).header("Authorization", "Bearer " + teacherA)).andExpect(status().isNotFound());
        mvc.perform(page(PAGE_DRAFT).header("Authorization", "Bearer " + teacherA)).andExpect(status().isOk());   // review needs the crops
    }

    @Test void the_admin_reads_every_school_and_one_school_when_she_picks_one() throws Exception {
        String token = adminToken();
        mvc.perform(page(PAGE_A).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(page(PAGE_B).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(page(PAGE_A).header("Authorization", "Bearer " + token).header(TenantContext.HEADER, B)).andExpect(status().isNotFound());
    }

    @Test void nobody_reads_media_without_a_token() throws Exception {
        mvc.perform(page(PAGE_A)).andExpect(status().isUnauthorized());
        mvc.perform(get("/media/child/whatever")).andExpect(status().isUnauthorized());
    }

    /** Status <em>and</em> body: a different message for "exists but is not yours" would be the oracle all over again. */
    @Test void an_unknown_id_and_a_hidden_one_look_the_same() throws Exception {
        String teacherB = jwt.issue("media-teacher-b", "media-teacher@beta.test", "TEACHER", B).token();
        var unknownPage = mvc.perform(page("no-such-page").header("Authorization", "Bearer " + teacherB)).andExpect(status().isNotFound()).andReturn();
        var hiddenPage = mvc.perform(page(PAGE_A).header("Authorization", "Bearer " + teacherB)).andExpect(status().isNotFound()).andReturn();
        org.assertj.core.api.Assertions.assertThat(hiddenPage.getResponse().getContentAsString()).isEqualTo(unknownPage.getResponse().getContentAsString());

        var child = parentPost("/children", "{\"name\":\"Yara\",\"avatarColor\":\"lavender\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"MEDAAA\"}");
        String mediaId = recording(child.get("id").asText());
        var unknownMedia = mvc.perform(get("/media/child/no-such-recording").header("Authorization", "Bearer " + teacherB)).andExpect(status().isNotFound()).andReturn();
        var otherSchool = mvc.perform(get("/media/child/" + mediaId).header("Authorization", "Bearer " + teacherB)).andExpect(status().isNotFound()).andReturn();
        var otherParent = mvc.perform(get("/media/child/" + mediaId).header("Authorization", "Bearer fake-token-a-stranger")).andExpect(status().isNotFound()).andReturn();
        org.assertj.core.api.Assertions.assertThat(otherSchool.getResponse().getContentAsString()).isEqualTo(unknownMedia.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(otherParent.getResponse().getContentAsString()).isEqualTo(unknownMedia.getResponse().getContentAsString());
    }

    @Test void a_child_recording_belongs_to_its_parent_and_to_her_school() throws Exception {
        var child = parentPost("/children", "{\"name\":\"Omar\",\"avatarColor\":\"sky\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"MEDAAA\"}");
        String mediaId = recording(child.get("id").asText());

        mvc.perform(get("/media/child/" + mediaId).header("Authorization", PARENT)).andExpect(status().isOk());
        mvc.perform(get("/media/child/" + mediaId).header("Authorization", "Bearer fake-token-someone-else")).andExpect(status().isNotFound());

        String teacherA = jwt.issue("media-teacher-a", "media-teacher@alpha.test", "TEACHER", A).token();
        String teacherB = jwt.issue("media-teacher-b", "media-teacher@beta.test", "TEACHER", B).token();
        mvc.perform(get("/media/child/" + mediaId).header("Authorization", "Bearer " + teacherA)).andExpect(status().isOk());
        mvc.perform(get("/media/child/" + mediaId).header("Authorization", "Bearer " + teacherB)).andExpect(status().isNotFound());
        mvc.perform(get("/media/child/" + mediaId).header("Authorization", "Bearer " + adminToken())).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder page(String id) { return get("/media/pages/" + id); }

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

    private void lesson(String id, String schoolId, String status) {
        if (lessons.existsById(id)) return;
        var l = new LessonEntity();
        l.setId(id); l.setSchoolId(schoolId); l.setClassId(schoolId + ":british:1:math"); l.setCourseId("british/1"); l.setSubject("math");
        l.setDate(LocalDate.of(2027, 5, 4)); l.setStatus(status); l.setVersion(1); l.setTitle("Lesson " + id); l.setSource("pdf");
        l.setCreatedAt(Instant.now()); l.setUpdatedAt(Instant.now());
        lessons.save(l);
    }

    private void page(String id, String lessonId) {
        if (pageImages.existsById(id)) return;
        String path = "test-media/" + id.replace(':', '-') + ".png";
        files.put(path, new byte[] {1, 2, 3, 4}, "image/png");
        var p = new PageImageEntity();
        p.setId(id); p.setLessonId(lessonId); p.setPageNumber(1); p.setStoragePath(path); p.setWidth(10); p.setHeight(10); p.setDescription("page");
        pageImages.save(p);
    }

    private void user(String id, String schoolId, String email) {
        if (users.existsById(id)) return;
        var u = new quest.server.auth.Entities.UserEntity();
        u.setId(id); u.setSchoolId(schoolId); u.setEmail(email); u.setPasswordHash("x"); u.setRole("TEACHER"); u.setStatus("active");
        u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
        users.save(u);
    }

    /** A recording row for a child, written straight through the repository (the upload route is `ParentFlowTest`'s). */
    private String recording(String childId) {
        String id = "media-rec-" + childId.substring(0, 8);
        String path = "test-media/" + id + ".m4a";
        files.put(path, new byte[] {9, 9, 9}, "audio/mp4");
        var m = new quest.server.children.Entities.ChildMediaEntity();
        m.setId(id); m.setChildId(childId); m.setStopId("stop-1"); m.setKind("recording"); m.setStoragePath(path);
        m.setMimeType("audio/mp4"); m.setSizeBytes(3); m.setCreatedAt(Instant.now());
        childMedia.save(m);
        return id;
    }
}
