package quest.server.exams;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import quest.server.grading.GradingTestSupport;

/**
 * N4.3's fixture: {@link GradingTestSupport}'s school, teacher, section, roster and hand-scored lesson, plus an
 * `exam_settings` row written straight to the table.
 *
 * <p><strong>Why the settings row is written rather than posted.</strong> `POST /teacher/classes/{id}/exams` runs
 * the lesson pipeline and refuses a day the school does not teach on, so a test that needs a window <em>open right
 * now</em> would only pass on five days out of seven — the failure mode `TestClock` exists because of. The create
 * route is exercised on its own, where the date is the thing under test.
 */
abstract class ExamTestSupport extends GradingTestSupport {
    @Autowired ExamSettingsRepository examSettings;
    @Autowired ExamAttemptRepository examSittings;

    /** An exam whose window runs from `opensIn` to `closesIn` minutes from now (negative = in the past). */
    Entities.ExamSettingsEntity exam(String lessonId, String schoolId, String level, long opensIn, long closesIn, String releaseMode) {
        var now = Instant.now();
        var row = examSettings.findById(lessonId).orElseGet(Entities.ExamSettingsEntity::new);
        row.setLessonId(lessonId); row.setSchoolId(schoolId); row.setLevel(level); row.setReleaseMode(releaseMode);
        row.setOpensAt(now.plus(opensIn, ChronoUnit.MINUTES)); row.setClosesAt(now.plus(closesIn, ChronoUnit.MINUTES));
        row.setCreatedAt(now); row.setUpdatedAt(now);
        return examSettings.save(row);
    }

    /** One attempt on one stop of the exam, as the app would upload it. */
    static String upload(String id, String lessonId, String stopId, boolean correct, int stars) {
        return "{\"id\":\"" + id + "\",\"stopId\":\"" + stopId + "\",\"lessonId\":\"" + lessonId
                + "\",\"level\":1,\"answerJson\":\"{}\",\"correct\":" + correct
                + ",\"attemptNumber\":1,\"mistakes\":0,\"stars\":" + stars + ",\"answeredAt\":" + System.currentTimeMillis() + "}";
    }

    static String batch(String... uploads) { return "[" + String.join(",", uploads) + "]"; }

    /** The fixture lesson's own stops, in the order {@link GradingTestSupport} writes them. */
    static String stop(String lessonId, int n) { return stopId(lessonId, n); }

    /** By school rather than by lesson id: an exam the create route made carries a UUID, not the test's prefix. */
    @Override public void removeSeed() {
        examSittings.deleteAll(examSittings.findAll().stream().filter(a -> a.getSchoolId().startsWith(prefix())).toList());
        examSettings.deleteAll(examSettings.findAll().stream().filter(e -> e.getSchoolId().startsWith(prefix())).toList());
        super.removeSeed();
    }
}
