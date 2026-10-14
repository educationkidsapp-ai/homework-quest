package quest.server.staffattendance;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.staffattendance.Entities.StaffAttendanceProfileEntity;
import quest.server.staffattendance.StaffAttendanceDto.StaffAttendanceProfile;
import quest.server.staffattendance.StaffAttendanceDto.StaffRef;
import quest.server.staffattendance.StaffAttendanceDto.UpdateStaffAttendanceProfilesRequest;
import quest.server.tenancy.TenantContext;

/**
 * Every employee's category and direct manager (feature 14): the list the staff pages read, and the one write the
 * edit form uses for one employee and the staff list uses for several ("Set direct manager").
 *
 * <p>A direct manager is any active teacher, coordinator or manager of the same school, and never the employee
 * herself. A category left unset is the role's default: TEACHER for a teacher, WORKER for a worker, ADMINISTRATIVE
 * for a coordinator or a manager.
 */
@Service
public class StaffAttendanceProfileService {
    static final int MAX_BATCH = 500;

    private final StaffAttendanceProfileRepository profiles; private final AttendanceStaff staff;
    private final TenantContext tenant; private final AuditService audit; private final Clock clock;

    public StaffAttendanceProfileService(StaffAttendanceProfileRepository profiles, AttendanceStaff staff, TenantContext tenant,
                                         AuditService audit, Clock clock) {
        this.profiles = profiles; this.staff = staff; this.tenant = tenant; this.audit = audit; this.clock = clock;
    }

    /** The role's category, for an employee with none set. */
    public static String defaultCategory(String role) {
        return switch (role) {
            case "TEACHER" -> StaffAttendanceDto.TEACHER;
            case "WORKER" -> StaffAttendanceDto.WORKER;
            default -> StaffAttendanceDto.ADMINISTRATIVE;
        };
    }

    /**
     * One employee's category: the one an Admin set on her profile, or her role's default. The school is named by the
     * caller rather than read from {@link TenantContext}, because feature 16's job and feature 19's run on the
     * scheduler's thread, which has no tenant.
     */
    public String categoryOf(String schoolId, String staffKind, String staffId, String role) {
        return profiles.findOneBySchoolIdAndStaffKindAndStaffId(schoolId, staffKind, staffId)
                .map(StaffAttendanceProfileEntity::getCategory).filter(java.util.Objects::nonNull)
                .orElseGet(() -> defaultCategory(role));
    }

    public List<StaffAttendanceProfile> list() {
        String schoolId = tenant.writeSchoolId();
        var employees = staff.all(schoolId);
        var rows = rowsByKey(schoolId);
        var names = new HashMap<String, String>();
        for (var e : employees) if (StaffAttendanceDto.KIND_USER.equals(e.kind())) names.put(e.id(), e.fullName());
        return employees.stream().map(e -> dto(e, rows.get(key(e.kind(), e.id())), names)).toList();
    }

    @Transactional
    public List<StaffAttendanceProfile> update(Principals.User caller, UpdateStaffAttendanceProfilesRequest request) {
        String schoolId = tenant.writeSchoolId();
        if (request.staff() == null || request.staff().isEmpty()) throw ApiException.badRequest("staff names at least one employee.");
        if (request.staff().size() > MAX_BATCH) throw ApiException.badRequest("At most " + MAX_BATCH + " employees at once.");
        boolean setsManager = request.directManagerId() != null || request.clearDirectManager();
        boolean setsCategory = request.category() != null || request.clearCategory();
        if (!setsManager && !setsCategory) throw ApiException.badRequest("Nothing to change: send directManagerId or category.");
        if (request.directManagerId() != null && request.clearDirectManager())
            throw ApiException.badRequest("Send directManagerId or clearDirectManager, not both.");
        if (request.category() != null && request.clearCategory())
            throw ApiException.badRequest("Send category or clearCategory, not both.");

        String category = null;
        if (request.category() != null) {
            category = request.category().trim().toUpperCase(Locale.ROOT);
            if (!StaffAttendanceDto.CATEGORIES.contains(category)) throw ApiException.badRequest("category is one of " + StaffAttendanceDto.CATEGORIES + ".");
        }
        String managerId = request.directManagerId();
        if (managerId != null && staff.user(schoolId, managerId).isEmpty())
            throw ApiException.badRequest("directManagerId is an active teacher, coordinator or manager of this school.");

        var employees = new ArrayList<AttendanceStaff.Employee>();
        var seen = new LinkedHashSet<String>();
        for (StaffRef ref : request.staff()) {
            String kind = ref.kind() == null ? "" : ref.kind().trim().toUpperCase(Locale.ROOT);
            var employee = staff.find(schoolId, kind, ref.id()).orElseThrow(() -> ApiException.notFound("staff"));
            if (managerId != null && StaffAttendanceDto.KIND_USER.equals(kind) && managerId.equals(employee.id()))
                throw ApiException.badRequest("An employee cannot be her own direct manager.");
            if (seen.add(key(kind, employee.id()))) employees.add(employee);
        }

        var rows = rowsByKey(schoolId);
        Instant now = Instant.now(clock);
        for (var e : employees) {
            var row = rows.computeIfAbsent(key(e.kind(), e.id()), k -> {
                var fresh = new StaffAttendanceProfileEntity();
                fresh.setId(UUID.randomUUID().toString()); fresh.setSchoolId(schoolId);
                fresh.setStaffKind(e.kind()); fresh.setStaffId(e.id());
                return fresh;
            });
            if (setsManager) row.setDirectManagerId(managerId);
            if (setsCategory) row.setCategory(category);
            row.setUpdatedAt(now);
            profiles.save(row);
        }
        audit.record(caller.userId(), "staff.attendance-profile.update", "school", schoolId, schoolId,
                Map.of("count", employees.size(), "manager", managerId == null ? "" : managerId,
                        "category", category == null ? "" : category));

        var names = new HashMap<String, String>();
        for (var e : staff.all(schoolId)) if (StaffAttendanceDto.KIND_USER.equals(e.kind())) names.put(e.id(), e.fullName());
        return employees.stream().map(e -> dto(e, rows.get(key(e.kind(), e.id())), names)).toList();
    }

    private Map<String, StaffAttendanceProfileEntity> rowsByKey(String schoolId) {
        var out = new HashMap<String, StaffAttendanceProfileEntity>();
        for (var row : profiles.findBySchoolId(schoolId)) out.put(key(row.getStaffKind(), row.getStaffId()), row);
        return out;
    }

    private static StaffAttendanceProfile dto(AttendanceStaff.Employee e, StaffAttendanceProfileEntity row, Map<String, String> names) {
        boolean categorySet = row != null && row.getCategory() != null;
        String managerId = row == null ? null : row.getDirectManagerId();
        return new StaffAttendanceProfile(e.kind(), e.id(), e.fullName(), e.role(),
                categorySet ? row.getCategory() : defaultCategory(e.role()), categorySet,
                managerId, managerId == null ? null : names.get(managerId));
    }

    private static String key(String kind, String id) { return kind + ":" + id; }
}
