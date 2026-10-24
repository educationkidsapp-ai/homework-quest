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
import java.util.List;
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
import quest.api.staff.RequestStage;
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
    private final AttendanceSettingsService settings; private final LeaveService leave;
    private final FeatureFlags flags; private final Clock clock;

    public AttendanceRequestService(StaffAttendanceRequestRepository requests, StaffCheckInRepository records,
                                    AttendanceStaff staff, DirectManagerResolver managers, AttendanceDays days,
                                    AttachmentRepository attachments, AttachmentService uploads, UserRepository users,
                                    NotificationService notifications, TenantContext tenant, SchoolCalendar calendar,
                                    AttendanceSettingsService settings, LeaveService leave,
                                    FeatureFlags flags, Clock clock) {
        this.requests = requests; this.records = records; this.staff = staff; this.managers = managers; this.days = days;
        this.attachments = attachments; this.uploads = uploads; this.users = users; this.notifications = notifications;
        this.tenant = tenant; this.calendar = calendar; this.settings = settings; this.leave = leave;
        this.flags = flags; this.clock = clock;
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
        } else if (AttendanceRequestType.LEAVE.equals(request.getType())) {
            requireLeave(schoolId);
            row.setType(AttendanceRequestType.LEAVE);
            leave(schoolId, caller, employee, row, request, date);
        } else if (AttendanceRequestType.PERMISSION.equals(request.getType())) {
            requireLeave(schoolId);
            row.setType(AttendanceRequestType.PERMISSION);
            permission(schoolId, employee, row, request, date);
        } else {
            throw ApiException.badRequest("type is REMOTE_DAY, CORRECTION, LEAVE or PERMISSION.");
        }
        row.setStage(stage(schoolId, row, today));
        requests.save(row);
        tellDeciders(schoolId, employee, row);
        return view(schoolId, row);
    }

    // ---------------------------------------------------------------- feature 34: a leave and a permission

    /**
     * A leave: the type is one the school offers, the range is a range, it uses the school days of her own schedule
     * (which is what the balance is spent in), the document rule is met, the balance holds, and nothing approved of
     * hers already covers one of those days.
     */
    private void leave(String schoolId, Principals.User caller, AttendanceStaff.Employee employee,
                       StaffAttendanceRequestEntity row, NewAttendanceRequest request, LocalDate from) {
        String type = AttendanceSettingsService.leaveType(request.getLeaveType());
        var rules = settings.leave(schoolId);
        if (!rules.enabled(type)) throw ApiException.badRequest("The school does not offer " + type + " leave.");
        LocalDate to = request.getToDate() == null || request.getToDate().isBlank() ? from : RemoteDayService.date(request.getToDate());
        if (to.isBefore(from)) throw ApiException.badRequest("toDate is on or after date.");
        if (from.plusDays(LeaveService.MAX_LEAVE_RANGE_DAYS).isBefore(to))
            throw ApiException.badRequest("A leave is at most " + LeaveService.MAX_LEAVE_RANGE_DAYS + " days long.");
        String category = leave.category(schoolId, employee);
        var dates = leave.schoolDays(schoolId, category, from, to);
        if (dates.isEmpty()) throw ApiException.badRequest("That range holds no working day of your schedule.");
        row.setToDay(to); row.setLeaveType(type); row.setDays(dates.size());

        Integer documentAfter = rules.documentAfterDays(type);
        boolean needsDocument = documentAfter != null && dates.size() > documentAfter;
        row.setAttachmentId(attachment(schoolId, caller, request.getAttachmentId()));
        if (needsDocument && row.getAttachmentId() == null)
            throw ApiException.badRequest("A " + type + " leave longer than " + documentAfter + " days needs a document.");

        Integer allowance = rules.allowance(type, category);
        if (allowance != null) {
            int used = leave.usedDays(schoolId, employee, type, from);
            if (used + dates.size() > allowance)
                throw ApiException.conflict("over_balance", "That leaves you " + (allowance - used)
                        + " of " + allowance + " days of " + type + " leave this year.");
        }
        noOverlap(schoolId, employee, from, to, null, null);
    }

    /** A permission: one day, a window inside it, no longer than the school allows, and not inside anything approved. */
    private void permission(String schoolId, AttendanceStaff.Employee employee, StaffAttendanceRequestEntity row,
                            NewAttendanceRequest request, LocalDate date) {
        var rules = settings.leave(schoolId);
        LocalTime from = hhmm(request.getFromTime(), "fromTime"), to = hhmm(request.getToTime(), "toTime");
        if (from == null || to == null) throw ApiException.badRequest("A permission needs fromTime and toTime, such as 07:00 and 09:00.");
        if (!from.isBefore(to)) throw ApiException.badRequest("toTime is after fromTime.");
        int minutes = (int) java.time.Duration.between(from, to).toMinutes();
        if (minutes > rules.permissionMaxMinutes())
            throw ApiException.badRequest("A permission is at most " + rules.permissionMaxMinutes() + " minutes.");
        row.setFromTime(from); row.setToTime(to); row.setMinutes(minutes);
        noOverlap(schoolId, employee, date, date, from, to);
    }

    /**
     * The spec's guardrail: no two <em>live</em> requests of hers may cover the same time — leave with leave,
     * permission with permission, or a permission inside a leave day. A pending request counts, so she cannot send
     * the same days twice and have both approved; a rejected or a cancelled one does not.
     */
    private void noOverlap(String schoolId, AttendanceStaff.Employee employee, LocalDate from, LocalDate to,
                           LocalTime fromTime, LocalTime toTime) {
        for (String type : List.of(AttendanceRequestType.LEAVE, AttendanceRequestType.PERMISSION))
            for (var other : requests.liveOfType(schoolId, employee.kind(), employee.id(), type)) {
                LocalDate otherTo = other.getToDay() == null ? other.getDay() : other.getToDay();
                if (from.isAfter(otherTo) || to.isBefore(other.getDay())) continue;
                boolean bothPermissions = fromTime != null && other.getFromTime() != null;
                if (bothPermissions && (!fromTime.isBefore(other.getToTime()) || !other.getFromTime().isBefore(toTime))) continue;
                throw ApiException.conflict("overlaps", "That overlaps a request of yours for " + other.getDay() + ".");
            }
    }

    /**
     * Which step a pending request waits for. The chain decides it — and a request for a <strong>past</strong> date
     * always waits for the Admin, whatever the chain says, because a day that is already recorded is being rewritten.
     */
    private String stage(String schoolId, StaffAttendanceRequestEntity row, LocalDate today) {
        if (!AttendanceRequestType.LEAVE.equals(row.getType()) && !AttendanceRequestType.PERMISSION.equals(row.getType()))
            return RequestStage.MANAGER;
        // Both chains start with her direct manager; a past date skips straight to the Admin.
        return row.getDay().isBefore(today) ? RequestStage.ADMIN : RequestStage.MANAGER;
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

    /** Feature 21's Home and feature 25's brief count her inbox: the status alone, no filter. */
    @Transactional(readOnly = true)
    public AttendanceRequestList toDecide(Principals.User caller, String status) {
        return toDecide(caller, status, null, null, null);
    }

    /**
     * The requests the caller may decide, newest first. {@code status} narrows them as feature 18 did; feature 34's
     * Requests page adds {@code type} (one request type), {@code staff} (`<kind>:<id>`) and {@code month} (`yyyy-MM`,
     * on the request's own day).
     *
     * <p>A pending leave or permission that has reached the ADMIN stage is left out for everybody but an Admin: her
     * manager has already decided her part of it and has nothing left to do with it.
     */
    @Transactional(readOnly = true)
    public AttendanceRequestList toDecide(Principals.User caller, String status, String type, String staff, String month) {
        String schoolId = tenant.writeSchoolId();
        var rows = status == null || status.isBlank() ? requests.findBySchoolIdOrderByCreatedAtDesc(schoolId)
                : requests.findBySchoolIdAndStatusOrderByCreatedAtDesc(schoolId, status.trim().toUpperCase(java.util.Locale.ROOT));
        String wantedType = type == null || type.isBlank() ? null : type.trim().toUpperCase(java.util.Locale.ROOT);
        String wantedStaff = staff == null || staff.isBlank() ? null : staff.trim();
        var ym = month == null || month.isBlank() ? null : AttendanceBoardService.month(month, today(schoolId));
        boolean admin = caller != null && "ADMIN".equals(caller.role());
        var out = new ArrayList<AttendanceRequestView>();
        for (var row : rows) {
            if (!mayDecide(schoolId, caller, row.getStaffKind(), row.getStaffId())) continue;
            if (wantedType != null && !wantedType.equals(row.getType())) continue;
            if (wantedStaff != null && !wantedStaff.equals(row.getStaffKind() + ":" + row.getStaffId())) continue;
            if (ym != null && !ym.equals(java.time.YearMonth.from(row.getDay()))) continue;
            if (!admin && AttendanceRequestStatus.PENDING.equals(row.getStatus())
                    && RequestStage.ADMIN.equals(row.getStage()) && isLeaveKind(row)) continue;
            out.add(view(schoolId, row));
        }
        return new AttendanceRequestList(out);
    }

    @Transactional
    public AttendanceRequestView approve(Principals.User caller, String id) { return decide(caller, id, true, null); }

    @Transactional
    public AttendanceRequestView reject(Principals.User caller, String id, String reason) { return decide(caller, id, false, reason); }

    /**
     * <strong>Feature 34's chain.</strong> A leave or a permission in a two-step school, approved by her manager at
     * the MANAGER stage, moves to the ADMIN stage and stays PENDING: it counts for nothing — no balance spent, no day
     * excused — until an Admin approves it too, and the Admin is told the same way the manager was. A rejection at
     * either stage ends it, and it <strong>needs a reason</strong>.
     *
     * <p>Approving a leave or a permission recomputes every day it touches ({@link AttendanceDays#recompute}), as a
     * correction does: a day that was already recorded late or absent becomes on leave, or has its minutes excused,
     * without waiting for the next run of feature 16's job.
     */
    private AttendanceRequestView decide(Principals.User caller, String id, boolean approve, String reason) {
        String schoolId = tenant.writeSchoolId();
        var row = requests.findOneBySchoolIdAndId(schoolId, id).orElseThrow(() -> ApiException.notFound("Request"));
        if (!mayDecide(schoolId, caller, row.getStaffKind(), row.getStaffId()))
            throw ApiException.forbidden("Only her direct manager or the Admin decides this request.");
        if (!AttendanceRequestStatus.PENDING.equals(row.getStatus()))
            throw ApiException.conflict("already_decided", "This request was already " + row.getStatus().toLowerCase(java.util.Locale.ROOT) + ".");
        boolean leaveKind = isLeaveKind(row);
        if (leaveKind && RequestStage.ADMIN.equals(row.getStage()) && !"ADMIN".equals(caller.role()))
            throw ApiException.forbidden("This request waits for the Admin.");
        String rejectReason = approve ? null : rejectReason(leaveKind, reason);
        var employee = staff.find(schoolId, row.getStaffKind(), row.getStaffId())
                .orElseThrow(() -> ApiException.conflict("not_an_employee", "This employee is no longer active."));
        Instant now = Instant.now(clock);

        if (approve && leaveKind && RequestStage.MANAGER.equals(row.getStage()) && settings.leave(schoolId).twoStep()) {
            row.setStage(RequestStage.ADMIN);                                    // step one of two: still pending
            requests.save(row);
            tellDeciders(schoolId, employee, row);
            return view(schoolId, row);
        }

        row.setStatus(approve ? AttendanceRequestStatus.APPROVED : AttendanceRequestStatus.REJECTED);
        row.setRejectReason(rejectReason); row.setStage(null);
        row.setDecidedBy(caller.userId()); row.setDecidedAt(now);
        if (approve && AttendanceRequestType.CORRECTION.equals(row.getType())) apply(schoolId, employee, row, caller.userId(), now);
        requests.save(row);
        if (approve && leaveKind) { requests.flush(); recomputeRange(schoolId, employee, row, now); }
        tellEmployee(schoolId, employee, row);
        return view(schoolId, row);
    }

    /** `POST /staff/attendance/requests/{id}/cancel`: the author withdraws her own request while it is pending. */
    @Transactional
    public AttendanceRequestView cancel(Principals.User caller, String id) {
        String schoolId = requireStaffApp();
        var employee = me(schoolId, caller);
        var row = requests.findOneBySchoolIdAndId(schoolId, id).orElseThrow(() -> ApiException.notFound("Request"));
        if (!employee.kind().equals(row.getStaffKind()) || !employee.id().equals(row.getStaffId()))
            throw ApiException.forbidden("Only the person who asked cancels her own request.");
        if (!AttendanceRequestStatus.PENDING.equals(row.getStatus()))
            throw ApiException.conflict("already_decided", "This request was already " + row.getStatus().toLowerCase(java.util.Locale.ROOT) + ".");
        row.setStatus(AttendanceRequestStatus.CANCELLED); row.setStage(null);
        row.setDecidedAt(Instant.now(clock));
        requests.save(row);
        return view(schoolId, row);
    }

    /** Every day an approved leave or permission touches, re-evaluated from the record as it now stands. */
    private void recomputeRange(String schoolId, AttendanceStaff.Employee employee, StaffAttendanceRequestEntity row, Instant now) {
        LocalDate to = row.getToDay() == null ? row.getDay() : row.getToDay();
        for (LocalDate date = row.getDay(); !date.isAfter(to); date = date.plusDays(1))
            days.recompute(schoolId, employee, date, now);
    }

    private static boolean isLeaveKind(StaffAttendanceRequestEntity row) {
        return AttendanceRequestType.LEAVE.equals(row.getType()) || AttendanceRequestType.PERMISSION.equals(row.getType());
    }

    /**
     * A rejection's reason. <strong>Mandatory for a leave and a permission</strong> (the spec) and optional for
     * feature 18's two types, whose released Android build rejects from the push with no body at all — so the bare
     * Reject button keeps working where it already did, and a leave it cannot word a reason for answers 400 and sends
     * the decider into the app.
     */
    private static String rejectReason(boolean leaveKind, String value) {
        String reason = value == null ? "" : value.trim();
        if (reason.isEmpty()) {
            if (leaveKind) throw ApiException.badRequest("A rejection needs a reason.");
            return null;
        }
        if (reason.length() > MAX_REASON) throw ApiException.badRequest("reason is 1–" + MAX_REASON + " characters.");
        return reason;
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
        row.setStatus(AttendanceRequestStatus.APPROVED); row.setStage(null);
        row.setCreatedBy(caller.userId()); row.setCreatedAt(now);
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

    /**
     * Who is told a request is waiting. Her resolved direct manager, or the Admins when the resolution falls back to
     * them — and, at feature 34's ADMIN stage (the second step of the chain, or a request for a past date), the
     * Admins, because they are the only people who can decide it.
     */
    private Set<String> deciders(String schoolId, AttendanceStaff.Employee employee, StaffAttendanceRequestEntity row) {
        var to = new LinkedHashSet<String>();
        if (row != null && isLeaveKind(row) && RequestStage.ADMIN.equals(row.getStage())) {
            to.addAll(users.findActiveAdminIds());
        } else {
            var resolved = managers.resolve(schoolId, employee.kind(), employee.id());
            if (resolved.source() == DirectManagerResolver.Source.ADMIN) to.addAll(users.findActiveAdminIds());
            else to.add(resolved.userId());
        }
        to.remove(null);
        to.remove(staff.account(employee));
        return to;
    }

    // ---------------------------------------------------------------- notifications

    /**
     * Feature 34 reuses {@link NotificationKind#ATTENDANCE_REQUEST} with the new link types
     * {@link AttendanceLink#REQUEST_LEAVE} and {@link AttendanceLink#REQUEST_PERMISSION}, and keeps
     * {@link AttendanceLink#REQUEST} — which is all `PushMessages.decidableRequestId` reads — so the released
     * Android build's <strong>Approve</strong> and <strong>Reject</strong> buttons work on a leave without a new app.
     */
    private void tellDeciders(String schoolId, AttendanceStaff.Employee employee, StaffAttendanceRequestEntity row) {
        String name = employee.fullName(), date = row.getDay().toString();
        String span = row.getToDay() == null || row.getToDay().equals(row.getDay()) ? date : date + " → " + row.getToDay();
        String window = row.getFromTime() == null ? date : date + " " + HH_MM.format(row.getFromTime()) + "–" + HH_MM.format(row.getToTime());
        String type, bodyEn, bodyAr;
        switch (row.getType()) {
            case AttendanceRequestType.REMOTE_DAY -> { type = AttendanceLink.REQUEST_REMOTE_DAY;
                bodyEn = name + " asks to work remotely on " + date; bodyAr = name + " يطلب العمل عن بعد يوم " + date; }
            case AttendanceRequestType.LEAVE -> { type = AttendanceLink.REQUEST_LEAVE;
                bodyEn = name + " asks for leave " + span; bodyAr = name + " يطلب إجازة " + span; }
            case AttendanceRequestType.PERMISSION -> { type = AttendanceLink.REQUEST_PERMISSION;
                bodyEn = name + " asks for a permission " + window; bodyAr = name + " يطلب استئذانًا " + window; }
            default -> { type = AttendanceLink.REQUEST_CORRECTION;
                bodyEn = name + " asks to correct " + date; bodyAr = name + " يطلب تصحيح حضور يوم " + date; }
        }
        var link = new LinkedHashMap<String, String>();
        link.put(AttendanceLink.TYPE, type);
        link.put(AttendanceLink.REQUEST, row.getId()); link.put(AttendanceLink.STAFF, employee.kind() + ":" + employee.id());
        link.put(AttendanceLink.NAME, name); link.put(AttendanceLink.DATE, date);
        if (row.getStage() != null) link.put(AttendanceLink.STAGE, row.getStage());
        String href = StaffAttendanceJob.link(link), collapse = "attendance:request:" + row.getId() + ":" + row.getStage();
        for (String to : deciders(schoolId, employee, row))
            notifications.attendance(schoolId, to, NotificationKind.ATTENDANCE_REQUEST, "Attendance request", bodyEn,
                    "طلب حضور", bodyAr, href, collapse);
    }

    private void tellEmployee(String schoolId, AttendanceStaff.Employee employee, StaffAttendanceRequestEntity row) {
        String to = staff.account(employee);
        if (to == null) return;
        boolean approved = AttendanceRequestStatus.APPROVED.equals(row.getStatus());
        String date = row.getDay().toString();
        String type, whatEn, whatAr;
        switch (row.getType()) {
            case AttendanceRequestType.REMOTE_DAY -> { type = AttendanceLink.DECIDED_REMOTE_DAY;
                whatEn = "Your remote day on " + date; whatAr = "طلب العمل عن بعد يوم " + date; }
            case AttendanceRequestType.LEAVE -> { type = AttendanceLink.DECIDED_LEAVE;
                whatEn = "Your leave from " + date; whatAr = "طلب الإجازة من " + date; }
            case AttendanceRequestType.PERMISSION -> { type = AttendanceLink.DECIDED_PERMISSION;
                whatEn = "Your permission on " + date; whatAr = "طلب الاستئذان يوم " + date; }
            default -> { type = AttendanceLink.DECIDED_CORRECTION;
                whatEn = "Your correction for " + date; whatAr = "طلب تصحيح يوم " + date; }
        }
        String reason = row.getRejectReason() == null ? "" : ": " + row.getRejectReason();
        String bodyEn = whatEn + (approved ? " was approved" : " was rejected" + reason);
        String bodyAr = whatAr + (approved ? " تمت الموافقة عليه" : " تم رفضه" + reason);
        var link = new LinkedHashMap<String, String>();
        link.put(AttendanceLink.TYPE, type);
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
                row.getDecidedAt() == null ? null : row.getDecidedAt().toEpochMilli(), originalIn, originalOut,
                row.getLeaveType(), row.getToDay() == null ? null : row.getToDay().toString(),
                row.getFromTime() == null ? null : HH_MM.format(row.getFromTime()),
                row.getToTime() == null ? null : HH_MM.format(row.getToTime()),
                row.getDays(), row.getMinutes(),
                AttendanceRequestStatus.PENDING.equals(row.getStatus()) ? row.getStage() : null, row.getRejectReason());
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

    /** Feature 34's own switch: a school with `staffLeave` off has no leave and no permission (404). */
    private void requireLeave(String schoolId) {
        if (!flags.isOn(schoolId, FlagKeys.STAFF_LEAVE))
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such endpoint.");
    }

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
