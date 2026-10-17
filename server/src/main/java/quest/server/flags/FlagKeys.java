package quest.server.flags;

import java.util.List;

/**
 * Every flag key in the code, so `@FeatureFlag("…")` can be checked against them and
 * `FlagAdminTest.the_flags_are_seeded_exactly_as_the_code_lists_them` can prove the seed and this list agree.
 * Adding a feature means a new key here <em>and</em> a new additive migration that seeds it: §4's fourteen come from
 * `V5__flags_themes.sql`, and the seven of the one-school build from `V7__sections.sql`.
 *
 * <p><b>D30 (owner, 2026-10-17), `V54__flags_on_by_default.sql`:</b> a switch is a kill switch, not a sales gate —
 * every key here is <em>on</em> by default and the Admin turns off what her school does not want on
 * Admin → Feature switches. A new key is therefore seeded {@code TRUE};
 * `FlagAdminTest.every_flag_except_multiSchool_is_on_by_default` fails when one is not. {@link #MULTI_SCHOOL} is the
 * single exception: it is a tenancy mode rather than a feature (D13) and stays off.
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

    /** N1.1, `docs/prompts/dashboard-first-one-school.md` §1, §5 and §6. */
    public static final String MULTI_SCHOOL = "multiSchool";
    public static final String WEB_PLAYER = "webPlayer";
    public static final String GRADEBOOK = "gradebook";
    public static final String OPEN_STOP_MARKING = "openStopMarking";
    public static final String EXAMS = "exams";
    public static final String TEACHER_ROSTER_EDIT = "teacher.rosterEdit";
    public static final String JOIN_BY_LIST = "join.byList";

    /** C1: parent ↔ teacher chat (`/children/{id}/chat/**`, `/teacher/chat/**`, `/ws/chat`). Seeded by V15. */
    public static final String CHAT = "chat";

    /**
     * Feature 13: staff (Admin, Manager, Coordinator, Teacher and the new Worker) sign in to the same MySchool app
     * the parents use — `/staff/**`, the staff half of `/me/devices` and the staff chat push. Seeded by V36.
     */
    public static final String STAFF_MOBILE_APP = "staffMobileApp";

    /** Feature 14: staff attendance — settings and direct managers now, check-in (15) and alerts (16) next. V37. */
    public static final String STAFF_ATTENDANCE = "staffAttendance";

    /**
     * Feature 19: a parent's message outside a member of staff's office hours arrives quietly and is summarised when
     * her next working day starts, and the parent's thread row says when that is. Seeded by V40.
     */
    public static final String CHAT_OFFICE_HOURS = "chatOfficeHours";

    /**
     * Feature 20: the floor supervisor role — her area `/floor/**`, her Messages, and the Admin's Floors and Floor
     * supervisors pages. Seeded by V42, and it stays off until the app build that can decode the new
     * `FLOOR_SUPERVISOR` chat role is distributed.
     */
    public static final String FLOOR_SUPERVISOR = "floorSupervisor";

    /**
     * Feature 21: the Admin's and the department manager's Home leads with what needs a decision today — attendance,
     * the requests waiting for her, overdue complaints and unread messages (`GET /me/today`). Seeded by V43.
     */
    public static final String TODAY_HOME = "todayHome";

    /**
     * Feature 22: a leader's voice note becomes a formal bilingual announcement she reviews and sends
     * (`POST /management/broadcasts/voice-drafts`). Speech to text runs on her device; the server takes text only.
     * Seeded by V44.
     */
    public static final String VOICE_ANNOUNCEMENT = "voiceAnnouncement";

    /**
     * Feature 23: a remote study day — the school, one academy or chosen grades study from home
     * (`/admin/remote-study-days`). It replaces feature 18's school-wide remote day, whose rows V46 copied into the
     * new table, so turning this on restores every day a school had already declared. Seeded by V46.
     */
    public static final String REMOTE_STUDY_DAYS = "remoteStudyDays";

    /**
     * Feature 25: the Admin's and the department manager's Home opens with a short bilingual brief of yesterday and
     * today (`GET /me/morning-brief`), written once a school morning by `MorningBriefJob`. The server counts the
     * facts and the AI only phrases them. Seeded by V47.
     */
    public static final String MORNING_BRIEF = "morningBrief";

    /**
     * Feature 26: the Admin and a department manager upload a circular — a PDF, a Word file or a photograph — and
     * read what it says in both languages, with its dates and actions (`/management/documents/**`). The file itself
     * is never stored. Seeded by V48.
     */
    public static final String DOCUMENT_READER = "documentReader";

    /**
     * Feature 27: a new complaint is sorted by the AI for the staff who answer it — category, urgency, a bilingual
     * summary and a suggested reply ({@code quest.server.chat.ComplaintTriage}). Seeded by V49. It gates the
     * triage only: the complaint itself, and the parent's expected reply time (which is `chatOfficeHours`), are
     * unaffected by it.
     */
    public static final String COMPLAINT_TRIAGE = "complaintTriage";

    /**
     * Feature 28: the parent's evening card about one child's day (`GET /children/{id}/evening-card` and the 18:00
     * push). Seeded by V50.
     */
    public static final String PARENT_EVENING_CARD = "parentEveningCard";

    /**
     * Feature 29: a parent reads the school's messages in her own language and staff read what she writes
     * (`PUT /parent/me/reading-language`, `POST /children/{id}/translate`, `POST /staff/translate`). The original
     * text is always the record; a translation is an addition, cached per language and labelled machine-translated.
     * Seeded by V51.
     */
    public static final String LANGUAGE_BRIDGE = "languageBridge";

    /**
     * Feature 30: teacher-approved hints on every question and one short practice set per weak skill, both written
     * only by the lesson's `HINTS` pipeline step at build time (`/teacher/lessons/{id}/smart-hints`). Seeded off by
     * V52, and nothing of it is published until the teacher has approved it.
     */
    public static final String SMART_HINTS = "smartHints";

    /**
     * Feature 31: each teacher hands in her weekly teaching material (دفتر التحضير) for every class and subject she
     * teaches, and the leaders of feature 17's board track it and mark it reviewed (`/teacher/materials/**`,
     * `/materials/**`, `/admin/material-settings`). Seeded by V53.
     */
    public static final String TEACHING_MATERIALS = "teachingMaterials";

    public static final List<String> ALL = List.of(
            LESSONS_PDF, LESSONS_SLIDES, LESSONS_IMAGES, LESSONS_MANUAL, LEVELS_THREE, RETELL_RECORDING,
            OPEN_ANSWER_DRAWING, PARENT_PANEL_ARABIC, COMPLAINTS, ANNOUNCEMENTS, TEACHER_QUESTIONS,
            STICKERS_TREASURE_CHEST, PROGRESS_WEEKLY_EMAIL, CERTIFICATES,
            MULTI_SCHOOL, WEB_PLAYER, GRADEBOOK, OPEN_STOP_MARKING, EXAMS, TEACHER_ROSTER_EDIT, JOIN_BY_LIST,
            CHAT, STAFF_MOBILE_APP, STAFF_ATTENDANCE, CHAT_OFFICE_HOURS, FLOOR_SUPERVISOR, TODAY_HOME,
            VOICE_ANNOUNCEMENT, REMOTE_STUDY_DAYS, MORNING_BRIEF, DOCUMENT_READER, COMPLAINT_TRIAGE, PARENT_EVENING_CARD, LANGUAGE_BRIDGE, SMART_HINTS,
            TEACHING_MATERIALS);
}
