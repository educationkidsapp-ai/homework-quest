package quest.server.materials;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import quest.api.dto.AttachmentRef;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.files.AttachmentRepository;
import quest.server.files.AttachmentService;
import quest.server.materials.Entities.MaterialSubmissionEntity;
import quest.server.staffattendance.StaffBoardScope;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;
import quest.server.tenancy.TeacherScope;
import quest.server.tenancy.TenantContext;

/**
 * Feature 31: the teacher's own teaching material and the leaders' board over it.
 *
 * <p><strong>What is expected is what she teaches.</strong> One row per (class, subject) of
 * `teaching_assignments` — never a hand-kept list — for each teaching week, so moving an assignment moves the
 * expectation with it. {@link TeacherScope#requireAssignment} guards the write, so a class or a subject that is not
 * hers is refused however the request is shaped.
 *
 * <p><strong>The leaders see feature 17's rows and nothing else.</strong> {@link StaffBoardScope} is
 * {@code staffattendance.BoardScope} itself, so the material board and the attendance board can never drift apart;
 * this class only drops the employees who have no teaching assignment, because a worker has no material to hand in.
 * A teacher outside the view is 404, the same answer as an id that does not exist.
 *
 * <p><strong>A review is a decision, not a field.</strong> The first review wins and names the leader and the time;
 * a second answers 409, as does a teacher trying to replace a row that has been reviewed.
 */
@Service
public class TeachingMaterialService {
    private static final int NOTE_MAX = 1000, COMMENT_MAX = 1000;

    private final MaterialSubmissionRepository submissions; private final MaterialDeadlines deadlines;
    private final ClassRepository sections;
    private final StaffBoardScope boardScope; private final TeacherScope teacherScope;
    private final AttachmentRepository attachments; private final AttachmentService uploads;
    private final UserRepository users; private final TenantContext tenant; private final Clock clock;

    public TeachingMaterialService(MaterialSubmissionRepository submissions, MaterialDeadlines deadlines,
                                   ClassRepository sections,
                                   StaffBoardScope boardScope, TeacherScope teacherScope,
                                   AttachmentRepository attachments, AttachmentService uploads,
                                   UserRepository users, TenantContext tenant, Clock clock) {
        this.submissions = submissions; this.deadlines = deadlines;
        this.sections = sections; this.boardScope = boardScope; this.teacherScope = teacherScope;
        this.attachments = attachments; this.uploads = uploads; this.users = users; this.tenant = tenant;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- the teacher's own page

    /** `GET /teacher/materials?week=`: her expected rows for one teaching week, with what she has handed in. */
    public MaterialDto.TeacherMaterials mine(Principals.User caller, String requestedWeek) {
        String schoolId = tenant.writeSchoolId();
        String teacherId = TeacherScope.require(caller).userId();
        LocalDate week = deadlines.week(schoolId, requestedWeek);
        Instant dueAt = deadlines.dueAt(schoolId, week);
        var rows = rowsOf(schoolId, teacherId, week, dueAt);
        return new MaterialDto.TeacherMaterials(week.toString(), dueAt.toString(), zone(schoolId), counts(rows), rows);
    }

    /** `POST /teacher/materials/attachments`: a PDF or a photograph of the preparation, stored without its metadata. */
    @Transactional
    public AttachmentRef upload(Principals.User caller, MultipartFile file) {
        String schoolId = tenant.writeSchoolId();
        var row = uploads.store(schoolId, TeacherScope.require(caller).userId(), file, quest.server.files.Entities.MATERIAL);
        return new AttachmentRef(row.getId(), row.getName(), row.getMimeType(), row.getSizeBytes(), row.getWidth(), row.getHeight());
    }

    /** `PUT /teacher/materials/{classId}/{subject}?week=`: hand it in, or replace it until it has been reviewed. */
    @Transactional
    public MaterialDto.MaterialRow submit(Principals.User caller, String classId, String subject, String requestedWeek,
                                          MaterialDto.SubmitMaterialRequest request) {
        String schoolId = tenant.writeSchoolId();
        String teacherId = TeacherScope.require(caller).userId();
        String wanted = normalise(subject);
        // `materials.self` is TEACHER alone, and `requireAssignment` refuses a TEACHER who holds no assignment on
        // that exact (class, subject) — so the row's teacher is always the caller, and never a path parameter.
        var section = teacherScope.requireAssignment(caller, classId, wanted);
        String attachmentId = trim(request == null ? null : request.attachmentId());
        String note = clip(trim(request == null ? null : request.note()), NOTE_MAX);
        if (attachmentId == null && note == null)
            throw ApiException.badRequest("Attach the material or write a note.");
        if (attachmentId != null) {
            var file = attachments.findOneById(attachmentId)
                    .filter(a -> a.isMaterial() && teacherId.equals(a.getUploadedBy()))
                    .orElseThrow(() -> ApiException.badRequest("That upload is not one of yours."));
            attachmentId = file.getId();
        }

        LocalDate week = deadlines.week(schoolId, requestedWeek);
        Instant dueAt = deadlines.dueAt(schoolId, week);
        var row = submissions.findOneBySchoolIdAndTeacherIdAndClassIdAndSubjectAndWeekStart(schoolId, teacherId, classId, wanted, week)
                .orElseGet(MaterialSubmissionEntity::new);
        if (row.getReviewedAt() != null)
            throw ApiException.conflict("already_reviewed", "That material has been reviewed; ask your leader to reopen it.");
        if (row.getId() == null) {
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId); row.setTeacherId(teacherId);
            row.setClassId(classId); row.setSubject(wanted); row.setWeekStart(week);
        }
        row.setAttachmentId(attachmentId); row.setNote(note); row.setSubmittedAt(Instant.now(clock));
        submissions.save(row);
        return row(section.getName(), classId, wanted, row, dueAt);
    }

    // ---------------------------------------------------------------- the leaders' board

    /** `GET /materials/board?week=`: one row per teacher the caller leads, with her (class, subject) rows. */
    public MaterialDto.MaterialBoard board(Principals.User caller, String requestedWeek) {
        String schoolId = tenant.writeSchoolId();
        var view = boardScope.of(schoolId, caller);
        LocalDate week = deadlines.week(schoolId, requestedWeek);
        Instant dueAt = deadlines.dueAt(schoolId, week);

        var teachers = teachersOf(view);
        var byTeacher = new LinkedHashMap<String, List<MaterialSubmissionEntity>>();
        for (var row : submissions.findBySchoolIdAndWeekStart(schoolId, week))
            byTeacher.computeIfAbsent(row.getTeacherId(), t -> new ArrayList<>()).add(row);
        var names = new HashMap<String, String>();

        var rows = new ArrayList<MaterialDto.MaterialBoardRow>();
        var all = new ArrayList<MaterialDto.MaterialRow>();
        for (var teacher : teachers) {
            var own = rows(schoolId, teacher.id(), byTeacher.getOrDefault(teacher.id(), List.of()), dueAt, names);
            if (own.isEmpty()) continue;                                      // no assignment, nothing expected
            all.addAll(own);
            rows.add(new MaterialDto.MaterialBoardRow(teacher.id(), teacher.fullName(), counts(own), own));
        }
        return new MaterialDto.MaterialBoard(week.toString(), dueAt.toString(), zone(schoolId), view.scope(),
                counts(all), List.copyOf(rows));
    }

    /** `POST /materials/{id}/review`: the first review wins; the second is a 409. */
    @Transactional
    public MaterialDto.MaterialRow review(Principals.User caller, String id, MaterialDto.ReviewMaterialRequest request) {
        String schoolId = tenant.writeSchoolId();
        var row = submissions.findOneBySchoolIdAndId(schoolId, id).orElseThrow(() -> ApiException.notFound("material"));
        boardScope.one(schoolId, caller, quest.server.staffattendance.StaffAttendanceDto.KIND_USER, row.getTeacherId());
        if (row.getReviewedAt() != null) throw ApiException.conflict("already_reviewed", "That material has already been reviewed.");
        row.setReviewedBy(caller.userId()); row.setReviewedAt(Instant.now(clock));
        row.setReviewComment(clip(trim(request == null ? null : request.comment()), COMMENT_MAX));
        submissions.save(row);
        return row(className(schoolId, row.getClassId(), new HashMap<>()), row.getClassId(), row.getSubject(), row,
                deadlines.dueAt(schoolId, row.getWeekStart()));
    }

    /**
     * {@link quest.server.files.MediaAccess}: a material file is read by its uploader (checked there) and by a leader
     * whose board scope covers the teacher it belongs to — the same scope the board itself answers with.
     */
    public boolean readable(quest.server.files.Entities.AttachmentEntity file, Principals.User caller) {
        if (caller == null) return false;
        var row = submissions.findFirstBySchoolIdAndAttachmentId(file.getSchoolId(), file.getId()).orElse(null);
        if (row == null) return false;
        try { boardScope.one(file.getSchoolId(), caller, quest.server.staffattendance.StaffAttendanceDto.KIND_USER, row.getTeacherId()); return true; }
        catch (ApiException e) { return false; }
    }

    // ---------------------------------------------------------------- rows

    /** The teachers of a board view: her employees who are `USER` rows with at least one teaching assignment. */
    private List<StaffBoardScope.Employee> teachersOf(StaffBoardScope.View view) {
        return view.employees().stream().filter(e -> quest.server.staffattendance.StaffAttendanceDto.KIND_USER.equals(e.kind())).toList();
    }

    private List<MaterialDto.MaterialRow> rowsOf(String schoolId, String teacherId, LocalDate week, Instant dueAt) {
        return rows(schoolId, teacherId, submissions.findBySchoolIdAndTeacherIdAndWeekStart(schoolId, teacherId, week),
                dueAt, new HashMap<>());
    }

    /** One teacher's expected rows for the week, in class then subject order, each with its submission or none. */
    private List<MaterialDto.MaterialRow> rows(String schoolId, String teacherId, List<MaterialSubmissionEntity> handed,
                                               Instant dueAt, Map<String, String> names) {
        var byKey = new HashMap<String, MaterialSubmissionEntity>();
        for (var row : handed) byKey.put(key(row.getClassId(), row.getSubject()), row);
        var out = new ArrayList<MaterialDto.MaterialRow>();
        for (var assignment : expected(teacherId)) {
            String subject = normalise(assignment.getSubject());
            out.add(row(className(schoolId, assignment.getClassId(), names), assignment.getClassId(), subject,
                    byKey.get(key(assignment.getClassId(), subject)), dueAt));
        }
        return List.copyOf(out);
    }

    private List<TeachingAssignmentEntity> expected(String teacherId) {
        var own = new ArrayList<>(teacherScope.assignmentsOf(teacherId));
        own.sort(Comparator.comparing(TeachingAssignmentEntity::getClassId).thenComparing(a -> normalise(a.getSubject())));
        return own;
    }

    private MaterialDto.MaterialRow row(String className, String classId, String subject,
                                        MaterialSubmissionEntity row, Instant dueAt) {
        if (row == null)
            return new MaterialDto.MaterialRow(null, classId, className, subject, MaterialDto.MISSING,
                    null, null, null, null, null, null, null, null);
        String status = row.getReviewedAt() != null ? MaterialDto.REVIEWED
                : row.getSubmittedAt().isAfter(dueAt) ? MaterialDto.LATE : MaterialDto.ON_TIME;
        String attachmentName = row.getAttachmentId() == null ? null
                : attachments.findOneById(row.getAttachmentId()).map(a -> a.getName()).orElse(null);
        return new MaterialDto.MaterialRow(row.getId(), classId, className, subject, status,
                row.getAttachmentId(), attachmentName, row.getNote(), row.getSubmittedAt().toString(),
                row.getReviewedBy(), reviewerName(row.getReviewedBy()),
                row.getReviewedAt() == null ? null : row.getReviewedAt().toString(), row.getReviewComment());
    }

    private static MaterialDto.MaterialCounts counts(List<MaterialDto.MaterialRow> rows) {
        int onTime = 0, late = 0, missing = 0, reviewed = 0;
        for (var r : rows) switch (r.status()) {
            case MaterialDto.ON_TIME -> onTime++;
            case MaterialDto.LATE -> late++;
            case MaterialDto.REVIEWED -> reviewed++;
            default -> missing++;
        }
        return new MaterialDto.MaterialCounts(onTime, late, missing, reviewed);
    }

    private String className(String schoolId, String classId, Map<String, String> cache) {
        return cache.computeIfAbsent(classId, id -> sections.findOneById(id)
                .map(c -> c.getName() == null || c.getName().isBlank() ? c.getSubject() : c.getName()).orElse(id));
    }

    private String reviewerName(String userId) {
        if (userId == null) return null;
        return users.findById(userId)
                .map(u -> u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getEmail() : u.getDisplayName())
                .orElse(null);
    }

    private String zone(String schoolId) { return deadlines.read(schoolId).timeZone(); }

    private static String key(String classId, String subject) { return classId + ":" + subject; }

    private static String normalise(String subject) {
        if (subject == null || subject.isBlank()) throw ApiException.badRequest("Name the subject.");
        return subject.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static String clip(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0, max);
    }
}
