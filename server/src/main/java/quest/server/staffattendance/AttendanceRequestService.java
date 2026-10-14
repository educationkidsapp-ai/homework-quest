package quest.server.staffattendance;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import quest.api.dto.AttachmentRef;
import quest.api.dto.AttendanceLink;
import quest.api.dto.NotificationKind;
import quest.api.staff.AdminCorrection;
import quest.api.staff.AttendanceRequestList;
import quest.api.staff.AttendanceRequestStatus;
import quest.api.staff.AttendanceRequestType;
import quest.api.staff.AttendanceRequestView;
import quest.api.staff.CheckInMode;
import quest.api.staff.CorrectionKind;
import quest.api.staff.NewAttendanceRequest;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.files.AttachmentRepository;
import quest.server.files.AttachmentService;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.notifications.NotificationService;
import quest.server.platform.SchoolCalendar;
import quest.server.staffattendance.Entities.StaffAttendanceRequestEntity;
import quest.server.staffattendance.Entities.StaffCheckInEntity;
import quest.server.tenancy.TenantContext;

/**
 * Feature 18: an employee asks for a remote day of her own or for a correction of a day, and the person who decides
 * approves or rejects it — from the dashboard, or from the Android push's buttons, which call the same routes.
 *
 * <p><strong>Who decides.</strong> Her direct manager as feature 14 resolves it ({@link DirectManagerResolver#resolve}:
 * the one the Admin named, else her academy director, else the Admins), or any Admin. When the resolution falls back to
 * the Admins, only an Admin decides. Nobody decides her own request. Everybody else gets 403 on approve and reject, and
 * does not see the request in {@link #toDecide}.
 *
 * <p><strong>What an approval does.</strong> A remote day is simply held: {@link RemoteDayService#appliesTo} reads it
 * at check-in. A correction is applied at once ({@link #apply}): the claimed time goes onto the day's record with mode
 * `correction`, who approved it and when; the times it replaced are kept on the record and the attempts log is not
 * touched; and the day's statuses are recomputed ({@link AttendanceDays#recompute}). A rejection changes nothing but
 * the request, and the employee is told either way.
 *
 * <p><strong>The server's clock.</strong> "Today" is the school's ({@link SchoolCalendar}), the claimed times are
 * wall-clock times on the requested day in the school's zone, and the decision is stamped with the injected clock.
 */
@Service
public class AttendanceRequestService {
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_REASON = 500;
    private static final Set<String> KINDS = Set.of(CorrectionKind.FORGOT_CHECK_IN, CorrectionKind.FORGOT_CHECK_OUT, CorrectionKind.PHONE_PROBLEM);

    private final StaffAttendanceRequestRepository requests; private final StaffCheckInRepository records;
    private final AttendanceStaff staff; private final DirectManagerResolver managers; private final AttendanceDays days;
    private final AttachmentRepository attachments; private final AttachmentService uploads; private final UserRepository users;
    private final NotificationService notifications; private final TenantContext tenant; private final SchoolCalendar calendar;
    private final FeatureFlags flags; private final Clock clock;

    public AttendanceRequestService(StaffAttendanceRequestRepository requests, StaffCheckInRepository records,
                                    AttendanceStaff staff, DirectManagerResolver managers, AttendanceDays days,
                                    AttachmentRepository attachments, AttachmentService uploads, UserRepository users,
                                    NotificationService notifications, TenantContext tenant, SchoolCalendar calendar,
                                    FeatureFlags flags, Clock clock) {
        this.requests = requests; this.records = records; this.staff = staff; this.managers = managers; this.days = days;
        this.attachments = attachments; this.uploads = uploads; this.users = users; this.notifications = notifications;
        this.tenant = tenant; this.calendar = calendar; this.flags = flags; this.clock = clock;
    }

    // ---------------------------------------------------------------- the employee's side

    /** `POST /staff/attendance/attachments`: a correction's photo or PDF, stored without its metadata. */
    @Transactional
    public AttachmentRef upload(Principals.User caller, MultipartFile file) {
        String schoolId = requireStaffApp();
        me(schoolId, caller);
        return ref(uploads.store(schoolId, caller.userId(), file, quest.server.files.Entities.ATTENDANCE));
    }

    @Transactional
    public AttendanceRequestView create(Principals.User caller, NewAttendanceRequest request) {
        String schoolId = requireStaffApp();
        var employee = me(schoolId, caller);
        LocalDate date = RemoteDayService.date(request.getDate());
        LocalDate today = today(schoolId);
        String reason = reason(request.getReason());

        var row = new StaffAttendanceRequestEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId);
        row.setStaffKind(employee.kind()); row.setStaffId(employee.id());
        row.setDay(date); row.setReason(reason); row.setStatus(AttendanceRequestStatus.PENDING);
        row.setCreatedBy(caller.userId()); row.setCreatedAt(Instant.now(clock));
        if (AttendanceRequestType.REMOTE_DAY.equals(request.getType())) {
            if (date.isBefore(today)) throw ApiException.badRequest("A remote day is today or later.");
            if (open(schoolId, employee, date, AttendanceRequestType.REMOTE_DAY))
                throw ApiException.conflict("already_requested", "You already asked for this day.");
            row.setType(AttendanceRequestType.REMOTE_DAY);
        } else if (AttendanceRequestType.CORRECTION.equals(request.getType())) {
            if (date.isAfter(today)) throw ApiException.badRequest("A correction is for today or an earlier day.");
            row.setType(AttendanceRequestType.CORRECTION);
            correction(row, request.getCorrectionKind(), request.getCheckIn(), request.getCheckOut());
            if (row.getClaimedCheckIn() == null && record(schoolId, employee, date) == null)
                throw ApiException.badRequest("That day has no check-in yet: claim the check-in time too.");
            row.setAttachmentId(attachment(schoolId, caller, request.getAttachmentId()));
        } else {
            throw ApiException.badRequest("type is REMOTE_DAY or CORRECTION.");
        }
        requests.save(row);
        tellDeciders(schoolId, employee, row);
        return view(schoolId, row);
    }

    @Transactional(readOnly = true)
    public AttendanceRequestList mine(Principals.User caller) {
        String schoolId = requireStaffApp();
        var employee = me(schoolId, caller);
        var out = new ArrayList<AttendanceRequestView>();
        for (var row : requests.findBySchoolIdAndStaffKindAndStaffIdOrderByCreatedAtDesc(schoolId, employee.kind(), employee.id()))
            out.add(view(schoolId, row));
        return new AttendanceRequestList(out);
    }

    // ---------------------------------------------------------------- the decider's side

    /** The requests the caller may decide, newest first, optionally narrowed to one status. */
    @Transactional(readOnly = true)
    public AttendanceRequestList toDecide(Principals.User caller, String status) {
        String schoolId = tenant.writeSchoolId();
        var rows = status == null || status.isBlank() ? requests.findBySchoolIdOrderByCreatedAtDesc(schoolId)
                : requests.findBySchoolIdAndStatusOrderByCreatedAtDesc(schoolId, status.trim().toUpperCase(java.util.Locale.ROOT));
        var out = new ArrayList<AttendanceRequestView>();
        for (var row : rows) if (mayDecide(schoolId, caller, row.getStaffKind(), row.getStaffId())) out.add(view(schoolId, row));
        return new AttendanceRequestList(out);
    }

    @Transactional
    public AttendanceRequestView approve(Principals.User caller, String id) { return decide(caller, id, true); }

    @Transactional
    public AttendanceRequestView reject(Principals.User caller, String id) { return decide(caller, id, false); }

    private AttendanceRequestView decide(Principals.User caller, String id, boolean approve) {
        String schoolId = tenant.writeSchoolId();
        var row = requests.findOneBySchoolIdAndId(schoolId, id).orElseThrow(() -> ApiException.notFound("Request"));
        if (!mayDecide(schoolId, caller, row.getStaffKind(), row.getStaffId()))
            throw ApiException.forbidden("Only her direct manager or the Admin decides this request.");
        if (!AttendanceRequestStatus.PENDING.equals(row.getStatus()))
            throw ApiException.conflict("already_decided", "This request was already " + row.getStatus().toLowerCase(java.util.Locale.ROOT) + ".");
        var employee = staff.find(schoolId, row.getStaffKind(), row.getStaffId())
                .orElseThrow(() -> ApiException.conflict("not_an_employee", "This employee is no longer active."));
        Instant now = Instant.now(clock);
        row.setStatus(approve ? AttendanceRequestStatus.APPROVED : AttendanceRequestStatus.REJECTED);
        row.setDecidedBy(caller.userId()); row.setDecidedAt(now);
        if (approve && AttendanceRequestType.CORRECTION.equals(row.getType())) apply(schoolId, employee, row, caller.userId(), now);
        requests.save(row);
        tellEmployee(schoolId, employee, row);
        return view(schoolId, row);
    }

    /**
     * `POST /admin/staff/{kind}/{id}/attendance-corrections`: the Admin fixes the day herself. The reason is mandatory;
     * the correction is written as a request already approved by her, so it carries the same audit as any other.
     */
    @Transactional
    public AttendanceRequestView adminCorrection(Principals.User caller, String kind, String staffId, AdminCorrection body) {
        String schoolId = tenant.writeSchoolId();
        var employee = staff.find(schoolId, kind, staffId).orElseThrow(() -> ApiException.notFound("Employee"));
        LocalDate date = RemoteDayService.date(body.getDate());
        if (date.isAfter(today(schoolId))) throw ApiException.badRequest("A correction is for today or an earlier day.");
        Instant now = Instant.now(clock);
        var row = new StaffAttendanceRequestEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId);
        row.setStaffKind(employee.kind()); row.setStaffId(employee.id()); row.setType(AttendanceRequestType.CORRECTION);
        row.setDay(date); row.setReason(reason(body.getReason()));
        correction(row, body.getCorrectionKind(), body.getCheckIn(), body.getCheckOut());
        if (row.getClaimedCheckIn() == null && record(schoolId, employee, date) == null)
            throw ApiException.badRequest("That day has no check-in yet: give the check-in time too.");
        row.setStatus(AttendanceRequestStatus.APPROVED); row.setCreatedBy(caller.userId()); row.setCreatedAt(now);
        row.setDecidedBy(caller.userId()); row.setDecidedAt(now);
        apply(schoolId, employee, row, caller.userId(), now);
        requests.save(row);
        return view(schoolId, row);
    }

    // ---------------------------------------------------------------- applying a correction

    /**
     * Writes the claimed times onto the day's record. The first time a record is corrected, the times it had are
     * copied to the `original_*` columns; a second correction keeps those, so the originals are always what the phone
     * recorded. The day's statuses are then recomputed from the corrected record.
     */
    private void apply(String schoolId, AttendanceStaff.Employee employee, StaffAttendanceRequestEntity request,
                       String approver, Instant now) {
        ZoneId zone = calendar.of(schoolId).zone();
        LocalDate date = request.getDay();
        var record = record(schoolId, employee, date);
        Instant in = request.getClaimedCheckIn() == null ? null : date.atTime(request.getClaimedCheckIn()).atZone(zone).toInstant();
        Instant out = request.getClaimedCheckOut() == null ? null : date.atTime(request.getClaimedCheckOut()).atZone(zone).toInstant();
        if (record == null) {
            record = new StaffCheckInEntity();
            record.setId(UUID.randomUUID().toString()); record.setSchoolId(schoolId);
            record.setStaffKind(employee.kind()); record.setStaffId(employee.id()); record.setDay(date);
            record.setCheckInDistanceM(0);
        } else {
            if (in != null && record.getOriginalCheckInAt() == null && record.getCorrectedAt() == null) record.setOriginalCheckInAt(record.getCheckInAt());
            if (out != null && record.getOriginalCheckOutAt() == null && record.getCorrectedAt() == null) record.setOriginalCheckOutAt(record.getCheckOutAt());
        }
        if (in != null) record.setCheckInAt(in);
        if (out != null) record.setCheckOutAt(out);
        if (record.getCheckOutAt() != null && !record.getCheckOutAt().isAfter(record.getCheckInAt()))
            throw ApiException.badRequest("The check-out must be after the check-in.");
        record.setMode(CheckInMode.CORRECTION);
        record.setCorrectedBy(approver); record.setCorrectedAt(now); record.setCorrectionRequestId(request.getId());
        records.save(record);
        days.recompute(schoolId, employee, date, now);
    }

    // ---------------------------------------------------------------- who decides

    /** Her resolved direct manager, or any Admin; never herself, and only an Admin when the resolution is the Admins. */
    boolean mayDecide(String schoolId, Principals.User caller, String kind, String staffId) {
        if (caller == null) return false;
        if (StaffAttendanceDto.KIND_USER.equals(kind) && staffId.equals(caller.userId())) return false;
        var employee = staff.find(schoolId, kind, staffId).orElse(null);
        if (employee != null && StaffAttendanceDto.KIND_WORKER.equals(kind) && caller.userId().equals(staff.account(employee))) return false;
        if ("ADMIN".equals(caller.role())) return true;
        var resolved = managers.resolve(schoolId, kind, staffId);
        return resolved.source() != DirectManagerResolver.Source.ADMIN && caller.userId().equals(resolved.userId());
    }

    /** `MediaAccess`: a correction's file is read by the uploader (checked there) and whoever may decide its request. */
    public boolean readable(quest.server.files.Entities.AttachmentEntity file, Principals.User caller) {
        var row = requests.findFirstBySchoolIdAndAttachmentId(file.getSchoolId(), file.getId()).orElse(null);
        return row != null && mayDecide(file.getSchoolId(), caller, row.getStaffKind(), row.getStaffId());
    }

    private Set<String> deciders(String schoolId, AttendanceStaff.Employee employee) {
        var to = new LinkedHashSet<String>();
        var resolved = managers.resolve(schoolId, employee.kind(), employee.id());
        if (resolved.source() == DirectManagerResolver.Source.ADMIN) to.addAll(users.findActiveAdminIds());
        else to.add(resolved.userId());
        to.remove(null);
        to.remove(staff.account(employee));
        return to;
    }

    // ---------------------------------------------------------------- notifications

    private void tellDeciders(String schoolId, AttendanceStaff.Employee employee, StaffAttendanceRequestEntity row) {
        boolean remote = AttendanceRequestType.REMOTE_DAY.equals(row.getType());
        String name = employee.fullName(), date = row.getDay().toString();
        String bodyEn = remote ? name + " asks to work remotely on " + date : name + " asks to correct " + date;
        String bodyAr = remote ? name + " يطلب العمل عن بعد يوم " + date : name + " يطلب تصحيح حضور يوم " + date;
        var link = new LinkedHashMap<String, String>();
        link.put(AttendanceLink.TYPE, remote ? AttendanceLink.REQUEST_REMOTE_DAY : AttendanceLink.REQUEST_CORRECTION);
        link.put(AttendanceLink.REQUEST, row.getId()); link.put(AttendanceLink.STAFF, employee.kind() + ":" + employee.id());
        link.put(AttendanceLink.NAME, name); link.put(AttendanceLink.DATE, date);
        String href = StaffAttendanceJob.link(link), collapse = "attendance:request:" + row.getId();
        for (String to : deciders(schoolId, employee))
            notifications.attendance(schoolId, to, NotificationKind.ATTENDANCE_REQUEST, "Attendance request", bodyEn,
                    "طلب حضور", bodyAr, href, collapse);
    }

    private void tellEmployee(String schoolId, AttendanceStaff.Employee employee, StaffAttendanceRequestEntity row) {
        String to = staff.account(employee);
        if (to == null) return;
        boolean remote = AttendanceRequestType.REMOTE_DAY.equals(row.getType());
        boolean approved = AttendanceRequestStatus.APPROVED.equals(row.getStatus());
        String date = row.getDay().toString();
        String bodyEn = (remote ? "Your remote day on " : "Your correction for ") + date + (approved ? " was approved" : " was rejected");
        String bodyAr = (remote ? "طلب العمل عن بعد يوم " : "طلب تصحيح يوم ") + date + (approved ? " تمت الموافقة عليه" : " تم رفضه");
        var link = new LinkedHashMap<String, String>();
        link.put(AttendanceLink.TYPE, remote ? AttendanceLink.DECIDED_REMOTE_DAY : AttendanceLink.DECIDED_CORRECTION);
        link.put(AttendanceLink.REQUEST, row.getId());
        link.put(AttendanceLink.DECISION, approved ? AttendanceLink.APPROVED : AttendanceLink.REJECTED);
        link.put(AttendanceLink.DATE, date);
        notifications.attendance(schoolId, to, NotificationKind.ATTENDANCE_DECISION, "Attendance request", bodyEn,
                "طلب حضور", bodyAr, StaffAttendanceJob.link(link), "attendance:decision:" + row.getId());
    }

    // ---------------------------------------------------------------- reading and validating

    private AttendanceRequestView view(String schoolId, StaffAttendanceRequestEntity row) {
        String name = staff.find(schoolId, row.getStaffKind(), row.getStaffId()).map(AttendanceStaff.Employee::fullName).orElse("—");
        AttachmentRef file = row.getAttachmentId() == null ? null
                : attachments.findById(row.getAttachmentId()).filter(a -> schoolId.equals(a.getSchoolId())).map(AttendanceRequestService::ref).orElse(null);
        String decidedBy = row.getDecidedBy() == null ? null
                : users.findById(row.getDecidedBy()).map(AttendanceStaff::name).orElse(null);
        String originalIn = null, originalOut = null;
        if (AttendanceRequestStatus.APPROVED.equals(row.getStatus()) && AttendanceRequestType.CORRECTION.equals(row.getType())) {
            var record = records.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(schoolId, row.getStaffKind(), row.getStaffId(), row.getDay()).orElse(null);
            if (record != null) {
                ZoneId zone = calendar.of(schoolId).zone();
                originalIn = time(record.getOriginalCheckInAt(), zone); originalOut = time(record.getOriginalCheckOutAt(), zone);
            }
        }
        return new AttendanceRequestView(row.getId(), row.getType(), row.getStatus(), row.getStaffKind() + ":" + row.getStaffId(),
                name, row.getDay().toString(), row.getReason(), row.getCorrectionKind(),
                row.getClaimedCheckIn() == null ? null : HH_MM.format(row.getClaimedCheckIn()),
                row.getClaimedCheckOut() == null ? null : HH_MM.format(row.getClaimedCheckOut()),
                file, row.getCreatedAt().toEpochMilli(), decidedBy,
                row.getDecidedAt() == null ? null : row.getDecidedAt().toEpochMilli(), originalIn, originalOut);
    }

    private static void correction(StaffAttendanceRequestEntity row, String kind, String in, String out) {
        if (kind == null || !KINDS.contains(kind)) throw ApiException.badRequest("correctionKind is FORGOT_CHECK_IN, FORGOT_CHECK_OUT or PHONE_PROBLEM.");
        LocalTime checkIn = hhmm(in, "checkIn"), checkOut = hhmm(out, "checkOut");
        if (checkIn == null && checkOut == null) throw ApiException.badRequest("Claim a check-in time, a check-out time or both.");
        if (CorrectionKind.FORGOT_CHECK_IN.equals(kind) && checkIn == null) throw ApiException.badRequest("A forgotten check-in needs its time.");
        if (CorrectionKind.FORGOT_CHECK_OUT.equals(kind) && checkOut == null) throw ApiException.badRequest("A forgotten check-out needs its time.");
        if (checkIn != null && checkOut != null && !checkOut.isAfter(checkIn)) throw ApiException.badRequest("The check-out must be after the check-in.");
        row.setCorrectionKind(kind); row.setClaimedCheckIn(checkIn); row.setClaimedCheckOut(checkOut);
    }

    /** The upload must be the caller's own, of this school, and a correction file — not a chat photo or a broadcast. */
    private String attachment(String schoolId, Principals.User caller, String id) {
        if (id == null || id.isBlank()) return null;
        var row = attachments.findById(id.trim()).orElse(null);
        if (row == null || !schoolId.equals(row.getSchoolId()) || !caller.userId().equals(row.getUploadedBy()) || !row.isAttendance())
            throw ApiException.badRequest("attachmentId is a file you uploaded to /staff/attendance/attachments.");
        if (requests.findFirstBySchoolIdAndAttachmentId(schoolId, row.getId()).isPresent())
            throw ApiException.conflict("attachment_already_sent", "That file is already attached to another request.");
        return row.getId();
    }

    private boolean open(String schoolId, AttendanceStaff.Employee employee, LocalDate date, String type) {
        return requests.findBySchoolIdAndStaffKindAndStaffIdOrderByCreatedAtDesc(schoolId, employee.kind(), employee.id()).stream()
                .anyMatch(r -> type.equals(r.getType()) && date.equals(r.getDay()) && !AttendanceRequestStatus.REJECTED.equals(r.getStatus()));
    }

    private StaffCheckInEntity record(String schoolId, AttendanceStaff.Employee employee, LocalDate date) {
        return records.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(schoolId, employee.kind(), employee.id(), date).orElse(null);
    }

    private static String reason(String value) {
        String reason = value == null ? "" : value.trim();
        if (reason.isEmpty() || reason.length() > MAX_REASON) throw ApiException.badRequest("reason is 1–" + MAX_REASON + " characters.");
        return reason;
    }

    private static LocalTime hhmm(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try { return LocalTime.parse(value.trim(), HH_MM); }
        catch (DateTimeParseException e) { throw ApiException.badRequest(field + " is a time such as 07:05."); }
    }

    private static String time(Instant at, ZoneId zone) { return at == null ? null : HH_MM.format(at.atZone(zone)); }

    private static AttachmentRef ref(quest.server.files.Entities.AttachmentEntity row) {
        return new AttachmentRef(row.getId(), row.getName(), row.getMimeType(), row.getSizeBytes(), row.getWidth(), row.getHeight());
    }

    private LocalDate today(String schoolId) { return LocalDate.ofInstant(Instant.now(clock), calendar.of(schoolId).zone()); }

    /** As feature 15's routes: the `/staff` area exists only when the school has staff mode on. */
    private String requireStaffApp() {
        String schoolId = tenant.writeSchoolId();
        if (!flags.isOn(schoolId, FlagKeys.STAFF_MOBILE_APP))
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such endpoint.");
        return schoolId;
    }

    private AttendanceStaff.Employee me(String schoolId, Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        return staff.forPrincipal(schoolId, caller)
                .orElseThrow(() -> ApiException.forbidden("Only an active employee of this school asks for her own attendance."));
    }
}
