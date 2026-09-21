package quest.server.flags;

import java.util.List;

/**
 * Every flag key in the code, so `@FeatureFlag("…")` can be checked against them and
 * `FlagAdminTest.the_flags_are_seeded_exactly_as_the_code_lists_them` can prove the seed and this list agree.
 * Adding a feature means a new key here <em>and</em> a new additive migration that seeds it: §4's fourteen come from
 * `V5__flags_themes.sql`, and the seven of the one-school build from `V7__sections.sql`.
 */
public final class FlagKeys {
    private FlagKeys() {}

    public static final String LESSONS_PDF = "lessons.pdf";
    public static final String LESSONS_SLIDES = "lessons.slides";
    public static final String LESSONS_IMAGES = "lessons.images";
    public static final String LESSONS_MANUAL = "lessons.manual";
    public static final String LEVELS_THREE = "levels.three";
    public static final String RETELL_RECORDING = "retell.recording";
    public static final String OPEN_ANSWER_DRAWING = "openAnswer.drawing";
    public static final String PARENT_PANEL_ARABIC = "parentPanel.arabic";
    public static final String COMPLAINTS = "complaints";
    public static final String ANNOUNCEMENTS = "announcements";
    public static final String TEACHER_QUESTIONS = "teacherQuestions";
    public static final String STICKERS_TREASURE_CHEST = "stickers.treasureChest";
    public static final String PROGRESS_WEEKLY_EMAIL = "progress.weeklyEmail";
    public static final String CERTIFICATES = "certificates";

    /** N1.1, `docs/prompts/dashboard-first-one-school.md` §1, §5 and §6. All seeded off. */
    public static final String MULTI_SCHOOL = "multiSchool";
    public static final String WEB_PLAYER = "webPlayer";
    public static final String GRADEBOOK = "gradebook";
    public static final String OPEN_STOP_MARKING = "openStopMarking";
    public static final String EXAMS = "exams";
    public static final String TEACHER_ROSTER_EDIT = "teacher.rosterEdit";
    public static final String JOIN_BY_LIST = "join.byList";

    /** C1: parent ↔ teacher chat (`/children/{id}/chat/**`, `/teacher/chat/**`, `/ws/chat`). Seeded off by V15. */
    public static final String CHAT = "chat";

    public static final List<String> ALL = List.of(
            LESSONS_PDF, LESSONS_SLIDES, LESSONS_IMAGES, LESSONS_MANUAL, LEVELS_THREE, RETELL_RECORDING,
            OPEN_ANSWER_DRAWING, PARENT_PANEL_ARABIC, COMPLAINTS, ANNOUNCEMENTS, TEACHER_QUESTIONS,
            STICKERS_TREASURE_CHEST, PROGRESS_WEEKLY_EMAIL, CERTIFICATES,
            MULTI_SCHOOL, WEB_PLAYER, GRADEBOOK, OPEN_STOP_MARKING, EXAMS, TEACHER_ROSTER_EDIT, JOIN_BY_LIST,
            CHAT);
}
