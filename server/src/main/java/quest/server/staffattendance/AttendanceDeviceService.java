package quest.server.staffattendance;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.staff.ApprovedDevice;
import quest.server.auth.AuditService;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.staffattendance.Entities.StaffAttendanceDeviceEntity;
import quest.server.tenancy.TenantContext;

/**
 * Feature 15's device binding: one phone per employee, bound by her first successful check-in and changed only by an
 * Admin's reset (`GET`/`DELETE /admin/staff/{kind}/{id}/attendance-device`).
 *
 * <p><strong>Why a binding at all.</strong> A geofence alone is satisfied by handing a colleague your phone, or by
 * installing the app twice. The install id is the app's own random string, so it proves nothing by itself — what it
 * buys is that the <em>same</em> phone has to be used every day, and that swapping to a new one is a visible act an
 * Admin performs and the audit log keeps. A reset is kept as history rather than an update: the row is stamped
 * {@code reset_by}/{@code reset_at} and the next check-in writes a new row, so which phones a person used over a year
 * is still answerable.
 *
 * <p>The audit entry carries the employee and nothing else. No attempt, record or audit entry in this feature holds
 * a latitude or a longitude.
 */
@Service
public class AttendanceDeviceService {
    private final StaffAttendanceDeviceRepository devices; private final AttendanceStaff staff;
    private final TenantContext tenant; private final AuditService audit; private final Clock clock;

    public AttendanceDeviceService(StaffAttendanceDeviceRepository devices, AttendanceStaff staff, TenantContext tenant,
                                   AuditService audit, Clock clock) {
        this.devices = devices; this.staff = staff; this.tenant = tenant; this.audit = audit; this.clock = clock;
    }

    /** The live binding, or empty when the employee has never checked in or her phone was just reset. */
    Optional<StaffAttendanceDeviceEntity> active(String schoolId, String kind, String staffId) {
        return devices.findOneBySchoolIdAndStaffKindAndStaffIdAndResetAtIsNull(schoolId, kind, staffId);
    }

    /** Binds this phone if nothing is bound. Called from a successful check-in only, inside its transaction. */
    void bind(String schoolId, String kind, String staffId, String installId, Instant now) {
        if (active(schoolId, kind, staffId).isPresent()) return;
        var row = new StaffAttendanceDeviceEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId);
        row.setStaffKind(kind); row.setStaffId(staffId);
        row.setInstallId(installId); row.setFirstSeenAt(now);
        devices.save(row);
    }

    // ---------------------------------------------------------------- the Admin's two routes

    /** `GET /admin/staff/{kind}/{id}/attendance-device`: the approved phone, or an empty shape when none is bound. */
    @Transactional(readOnly = true)
    public ApprovedDevice read(String kind, String staffId) {
        String schoolId = tenant.writeSchoolId();
        var employee = employee(schoolId, kind, staffId);
        return active(schoolId, employee.kind(), employee.id()).map(AttendanceDeviceService::dto).orElseGet(AttendanceDeviceService::none);
    }

    /**
     * `DELETE` on the same path: the binding is stamped and the answer is the empty shape, because from here until
     * her next successful check-in the employee has no approved phone.
     */
    @Transactional
    public ApprovedDevice reset(Principals.User caller, String kind, String staffId) {
        String schoolId = tenant.writeSchoolId();
        var employee = employee(schoolId, kind, staffId);
        active(schoolId, employee.kind(), employee.id()).ifPresent(row -> {
            row.setResetBy(caller.userId()); row.setResetAt(Instant.now(clock));
            devices.save(row);
        });
        audit.record(caller.userId(), "attendance.device.reset", "staff", employee.kind() + ":" + employee.id(), schoolId,
                Map.of("kind", employee.kind()));
        return none();
    }

    /** An employee of the caller's school, or 404 — an unknown kind and another school's staff are the same answer. */
    private AttendanceStaff.Employee employee(String schoolId, String kind, String staffId) {
        String normalised = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (!StaffAttendanceDto.KIND_USER.equals(normalised) && !StaffAttendanceDto.KIND_WORKER.equals(normalised))
            throw ApiException.notFound("staff");
        return staff.find(schoolId, normalised, staffId).orElseThrow(() -> ApiException.notFound("staff"));
    }

    private static ApprovedDevice dto(StaffAttendanceDeviceEntity row) {
        return new ApprovedDevice(row.getInstallId(), row.getFirstSeenAt().toString());
    }

    private static ApprovedDevice none() { return new ApprovedDevice(null, null); }
}
