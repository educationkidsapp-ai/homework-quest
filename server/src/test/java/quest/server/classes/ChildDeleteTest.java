package quest.server.classes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.children.Entities.AttemptEntity;
import quest.server.children.Entities.ChildEntity;
import quest.server.children.Entities.ChildMediaEntity;
import quest.server.children.Entities.LessonCompletionEntity;
import quest.server.children.Entities.ParentUnlockEntity;
import quest.server.children.Entities.StickerEntity;
import quest.server.children.Entities.StopCompletionEntity;
import quest.server.children.Entities.StreakEntity;

/**
 * `DELETE /admin/children/{id}`: the way a school's acceptance or test data is actually removed, as opposed to
 * retired. Everything that was only ever about this child goes with her — the roster place, her attempts, both kinds
 * of completion, her unlocks, stickers, streak, media and her answers to a teacher's questions.
 */
class ChildDeleteTest extends ClassesTestSupport {
    private static final String A = "cd-school-a", B = "cd-school-b", TEACHER = "cd-teacher";
    private String admin, section;

    @Autowired quest.server.children.AttemptRepository attempts;
    @Autowired quest.server.children.StopCompletionRepository stopCompletions;
    @Autowired quest.server.children.LessonCompletionRepository lessonCompletions;
    @Autowired quest.server.children.ParentUnlockRepository unlocks;
    @Autowired quest.server.children.StickerRepository stickers;
    @Autowired quest.server.children.StreakRepository streaks;
    @Autowired quest.server.children.ChildMediaRepository media;
    @Autowired quest.server.teacher.TeacherQuestionRepository questions;
    @Autowired quest.server.teacher.TeacherQuestionAnswerRepository answers;

    @Override String prefix() { return "cd-"; }

    @BeforeEach void seed() throws Exception {
        school(A, "Delete School", "CDAAAA"); school(B, "Other School", "CDBBBB");
        user(TEACHER, A, "teacher@cd.test", "TEACHER");
        admin = adminToken();
        section = json(mvc.perform(scoped(post("/admin/classes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"british\",\"grade\":1,\"name\":\"1A British\"}"), admin, A))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    /** The answers point at the children, so they go first or `removeSeed`'s delete hits the constraint. */
    @AfterEach void cleanUp() {
        answers.deleteAll(answers.findAll().stream().filter(a -> a.getId().startsWith("cd-")).toList());
        questions.deleteAll(questions.findAll().stream().filter(q -> q.getId().startsWith("cd-")).toList());
        removeSeed();
        users.deleteAll(users.findAll().stream().filter(u -> u.getId().startsWith("cd-")).toList());
    }

    @Test void deleting_a_child_takes_her_whole_history_with_her() throws Exception {
        var child = appChild(A, "Rami");
        mvc.perform(scoped(post("/admin/classes/" + section + "/roster/attach").contentType(MediaType.APPLICATION_JSON)
                .content("{\"childId\":\"" + child.getId() + "\"}"), admin, A)).andExpect(status().isOk());
        String question = question(A);
        history(child.getId(), question);

        mvc.perform(scoped(delete("/admin/children/" + child.getId()), admin, A)).andExpect(status().isNoContent());

        assertThat(childRows.findById(child.getId())).as("the row itself, not a `deleted_at`").isEmpty();
        assertThat(attempts.findByChildIdOrderByAnsweredAtDesc(child.getId())).isEmpty();
        assertThat(stopCompletions.findByChildId(child.getId())).isEmpty();
        assertThat(lessonCompletions.findByChildId(child.getId())).isEmpty();
        assertThat(unlocks.findByChildId(child.getId())).isEmpty();
        assertThat(stickers.findByChildIdOrderByEarnedAt(child.getId())).isEmpty();
        assertThat(streaks.findById(child.getId())).isEmpty();
        assertThat(media.findByChildId(child.getId())).isEmpty();
        assertThat(answers.findByChildIdOrderByAnsweredAtDesc(child.getId())).as("the one child table with no cascade").isEmpty();
        // the question she answered is the teacher's and stays
        assertThat(questions.findById(question)).isPresent();
        assertThat(json(mvc.perform(scoped(get("/admin/classes/" + section + "/children"), admin, A)).andReturn())).isEmpty();
    }

    /** A child a parent registered and nobody put in a section — exactly what an acceptance run leaves behind. */
    @Test void a_child_on_no_roster_is_deletable_too() throws Exception {
        var child = appChild(A, "Unsectioned");
        assertThat(json(mvc.perform(scoped(get("/admin/children?unassigned=true"), admin, A)).andReturn()).findValuesAsText("id")).contains(child.getId());
        mvc.perform(scoped(delete("/admin/children/" + child.getId()), admin, A)).andExpect(status().isNoContent());
        assertThat(childRows.findById(child.getId())).isEmpty();
    }

    /** Another school's child is a 404, never a refusal that confirms she exists — and she is still there afterwards. */
    @Test void another_schools_child_is_not_found() throws Exception {
        var theirs = appChild(B, "Theirs");
        mvc.perform(scoped(delete("/admin/children/" + theirs.getId()), admin, A)).andExpect(status().isNotFound());
        assertThat(childRows.findById(theirs.getId())).isPresent();
    }

    /** The other half of the pair: `active=false` retires her and keeps everything, delete is the one that removes. */
    @Test void deactivating_keeps_the_child_and_her_work() throws Exception {
        var child = appChild(A, "Retired");
        mvc.perform(scoped(post("/admin/classes/" + section + "/roster/attach").contentType(MediaType.APPLICATION_JSON)
                .content("{\"childId\":\"" + child.getId() + "\"}"), admin, A)).andExpect(status().isOk());
        history(child.getId(), question(A));

        var updated = json(mvc.perform(scoped(patch("/admin/children/" + child.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"), admin, A)).andExpect(status().isOk()).andReturn());
        assertThat(updated.get("active").asBoolean()).isFalse();
        assertThat(childRows.findById(child.getId())).isPresent();
        assertThat(attempts.findByChildIdOrderByAnsweredAtDesc(child.getId())).isNotEmpty();
    }

    // ---------------------------------------------------------------- fixtures

    private ChildEntity appChild(String schoolId, String name) {
        var child = new ChildEntity();
        child.setId("cd-" + UUID.randomUUID()); child.setSchoolId(schoolId); child.setName(name);
        child.setCurriculum("british"); child.setGrade(1); child.setAvatarColor("sky"); child.setLanguages("en");
        child.setActive(true); child.setCreatedAt(Instant.now());
        return childRows.save(child);
    }

    private String question(String schoolId) {
        var q = new quest.server.teacher.Entities.TeacherQuestionEntity();
        q.setId("cd-q-" + UUID.randomUUID()); q.setSchoolId(schoolId); q.setTeacherId(TEACHER); q.setTitle("Quick check");
        q.setFromDate(LocalDate.of(2026, 9, 1)); q.setToDate(LocalDate.of(2026, 9, 30)); q.setCreatedAt(Instant.now());
        return questions.save(q).getId();
    }

    /** One row in every table that hangs off a child, so the delete has something to cascade through. */
    private void history(String childId, String questionId) {
        var a = new AttemptEntity();
        a.setId("cd-a-" + UUID.randomUUID()); a.setChildId(childId); a.setStopId("cd-stop"); a.setLessonId("cd-lesson");
        a.setLevel(1); a.setAnswerJson("{}"); a.setCorrect(true); a.setAttemptNumber(1); a.setStars(2); a.setAnsweredAt(Instant.now());
        attempts.save(a);
        var sc = new StopCompletionEntity();
        sc.setChildId(childId); sc.setStopId("cd-stop"); sc.setLessonId("cd-lesson"); sc.setLevel(1); sc.setStars(2); sc.setCompletedAt(Instant.now());
        stopCompletions.save(sc);
        var lc = new LessonCompletionEntity();
        lc.setChildId(childId); lc.setLessonId("cd-lesson"); lc.setLevel(1); lc.setStarsEarned(2); lc.setStarsTotal(3); lc.setCompletedAt(Instant.now());
        lessonCompletions.save(lc);
        var unlock = new ParentUnlockEntity();
        unlock.setChildId(childId); unlock.setLessonId("cd-lesson"); unlock.setLevel(2);
        unlocks.save(unlock);
        var sticker = new StickerEntity();
        sticker.setId("cd-s-" + UUID.randomUUID()); sticker.setChildId(childId); sticker.setStickerKey("star"); sticker.setEarnedAt(Instant.now());
        stickers.save(sticker);
        var streak = new StreakEntity();
        streak.setChildId(childId); streak.setCurrentDays(3); streak.setLastPlayedDate(LocalDate.of(2026, 9, 18));
        streaks.save(streak);
        var m = new ChildMediaEntity();
        m.setId("cd-m-" + UUID.randomUUID()); m.setChildId(childId); m.setStopId("cd-stop"); m.setKind("recording");
        m.setStoragePath("media/" + childId + "/cd-stop.m4a"); m.setMimeType("audio/mp4"); m.setSizeBytes(12); m.setCreatedAt(Instant.now());
        media.save(m);
        var answer = new quest.server.teacher.Entities.TeacherQuestionAnswerEntity();
        answer.setId("cd-ans-" + UUID.randomUUID()); answer.setSchoolId(A); answer.setQuestionId(questionId); answer.setChildId(childId);
        answer.setStopId("cd-stop"); answer.setAnswerJson("{}"); answer.setCorrect(true); answer.setStars(1); answer.setAnsweredAt(Instant.now());
        answers.save(answer);
    }
}
