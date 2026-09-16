package quest.server.flags;

import java.util.List;

/**
 * The 14 keys of §4, in the code so `@FeatureFlag("…")` can be checked against them and `FlagContractTest` can prove
 * the seed in `V5__flags_themes.sql` and this list agree. Adding a feature means a new key here <em>and</em> a new
 * additive migration that seeds it.
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

    public static final List<String> ALL = List.of(
            LESSONS_PDF, LESSONS_SLIDES, LESSONS_IMAGES, LESSONS_MANUAL, LEVELS_THREE, RETELL_RECORDING,
            OPEN_ANSWER_DRAWING, PARENT_PANEL_ARABIC, COMPLAINTS, ANNOUNCEMENTS, TEACHER_QUESTIONS,
            STICKERS_TREASURE_CHEST, PROGRESS_WEEKLY_EMAIL, CERTIFICATES);
}
