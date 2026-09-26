package quest.server.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.Principals;
import quest.server.children.ChildRepository;
import quest.server.children.Entities.ChildEntity;
import quest.server.config.ApiException;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.TeacherScope;

@Service
public class AttendanceService {

    private static final Set<String> VALID_STATUSES = Set.of("PRESENT", "ABSENT", "LATE", "EXCUSED");

    private final AttendanceRepository attendance;
    private final ChildRepository children;
    private final TeacherScope scope;
    private final AuditService audit;

    public AttendanceService(AttendanceRepository attendance, ChildRepository children,
                             TeacherScope scope, AuditService audit) {
        this.attendance = attendance;
        this.children = children;
        this.scope = scope;
        this.audit = audit;
    }

    /**
     * Reads attendance for all students on a section's active roster for a given date.
     */
    @Transactional(readOnly = true)
    public AttendanceDto.ClassAttendanceResponse getClassAttendance(Principals.User caller, String classId, LocalDate date) {
        TeacherScope.require(caller);
        ClassEntity section = scope.requireClass(caller, classId);
        LocalDate targetDate = date != null ? date : LocalDate.now();

        List<ChildEntity> roster = children.findByClassIdAndActiveTrueAndDeletedAtIsNullOrderByNameAsc(classId);
        List<AttendanceEntity> recorded = attendance.findBySectionIdAndDate(classId, targetDate);
        Map<String, AttendanceEntity> byChild = recorded.stream()
                .collect(Collectors.toMap(AttendanceEntity::getChildId, Function.identity(), (a, b) -> a));
        return dayOf(section, targetDate, roster, byChild);
    }

    /**
     * R3: the same per-day body, once per day of a window, for a section a caller has <em>already</em> been scoped
     * to — `/coordinator/classes/{id}/attendance` resolves it through {@link quest.server.tenancy.CoordinatorScope}
     * and hands it over, because {@link TeacherScope} would answer a coordinator a question about a teacher.
     *
     * <p>Two statements whatever the window: the roster once, and the window's rows once. Calling
     * {@link #getClassAttendance} per day would be two per day, which is a hundred and twenty for a month.
     */
    @Transactional(readOnly = true)
    public List<AttendanceDto.ClassAttendanceResponse> classAttendanceWindow(ClassEntity section, LocalDate from, LocalDate to) {
        var roster = children.findByClassIdAndActiveTrueAndDeletedAtIsNullOrderByNameAsc(section.getId());
        var byDate = new java.util.HashMap<LocalDate, Map<String, AttendanceEntity>>();
        for (AttendanceEntity row : attendance.findBySectionIdAndDateBetweenOrderByDateAsc(section.getId(), from, to))
            byDate.computeIfAbsent(row.getDate(), d -> new java.util.HashMap<>()).putIfAbsent(row.getChildId(), row);
        var days = new ArrayList<AttendanceDto.ClassAttendanceResponse>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1))
            days.add(dayOf(section, day, roster, byDate.getOrDefault(day, Map.of())));
        return List.copyOf(days);
    }

    /** One day of one section: the roster in name order, each child's row or `NOT_MARKED`, and the five counts. */
    private AttendanceDto.ClassAttendanceResponse dayOf(ClassEntity section, LocalDate targetDate,
                                                       List<ChildEntity> roster, Map<String, AttendanceEntity> byChild) {
        List<AttendanceDto.ClassAttendanceItem> items = new ArrayList<>();
        int present = 0, absent = 0, late = 0, excused = 0;

        for (ChildEntity child : roster) {
            AttendanceEntity rec = byChild.get(child.getId());
            String status = rec != null ? rec.getStatus() : "NOT_MARKED";
            String notes = rec != null ? rec.getNotes() : null;
            Instant updatedAt = rec != null ? rec.getUpdatedAt() : null;

            switch (status) {
                case "PRESENT" -> present++;
                case "ABSENT" -> absent++;
                case "LATE" -> late++;
                case "EXCUSED" -> excused++;
            }

            items.add(new AttendanceDto.ClassAttendanceItem(
                    child.getId(),
                    child.getName(),
                    child.getAvatarColor(),
                    child.getPhotoUrl(),
                    status,
                    notes,
                    updatedAt
            ));
        }

        int total = roster.size();
        int markedCount = present + absent + late + excused;
        double rate = markedCount > 0 ? ((double) (present + late) / markedCount) * 100.0 : 100.0;
        double roundedRate = Math.round(rate * 10.0) / 10.0;

        return new AttendanceDto.ClassAttendanceResponse(
                section.getId(),
                section.getName(),
                targetDate,
                items,
                total,
                present,
                absent,
                late,
                excused,
                roundedRate
        );
    }

    /**
     * Saves or updates attendance for students in a class section on a given date.
     */
    @Transactional
    public AttendanceDto.ClassAttendanceResponse saveClassAttendance(Principals.User caller, String classId, AttendanceDto.SaveAttendanceRequest request) {
        TeacherScope.require(caller);
        ClassEntity section = scope.requireClass(caller, classId);
        LocalDate targetDate = request.date() != null ? request.date() : LocalDate.now();

        if (request.items() == null || request.items().isEmpty()) {
            throw ApiException.badRequest("items list must not be empty");
        }

        Instant now = Instant.now();
        List<ChildEntity> roster = children.findByClassIdAndActiveTrueAndDeletedAtIsNullOrderByNameAsc(classId);
        Set<String> validChildIds = roster.stream().map(ChildEntity::getId).collect(Collectors.toSet());

        for (AttendanceDto.SaveAttendanceItem item : request.items()) {
            if (item.childId() == null || !validChildIds.contains(item.childId())) {
                continue;
            }
            String rawStatus = item.status() == null ? "PRESENT" : item.status().trim().toUpperCase(Locale.ROOT);
            if (!VALID_STATUSES.contains(rawStatus)) {
                throw ApiException.badRequest("Unknown attendance status: " + item.status());
            }

            var existing = attendance.findByChildIdAndDate(item.childId(), targetDate);
            if (existing.isPresent()) {
                AttendanceEntity entity = existing.get();
                entity.setSectionId(classId);
                entity.setStatus(rawStatus);
                entity.setNotes(item.notes() != null && !item.notes().isBlank() ? item.notes().trim() : null);
                entity.setMarkedBy(caller.userId());
                entity.setUpdatedAt(now);
                attendance.save(entity);
            } else {
                AttendanceEntity entity = new AttendanceEntity();
                entity.setId(UUID.randomUUID().toString());
                entity.setSchoolId(section.getSchoolId());
                entity.setSectionId(classId);
                entity.setChildId(item.childId());
                entity.setDate(targetDate);
                entity.setStatus(rawStatus);
                entity.setNotes(item.notes() != null && !item.notes().isBlank() ? item.notes().trim() : null);
                entity.setMarkedBy(caller.userId());
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                attendance.save(entity);
            }
        }

        audit.record(caller.userId(), "attendance.save", "class", classId, section.getSchoolId(),
                Map.of("date", targetDate.toString(), "count", String.valueOf(request.items().size())));

        return getClassAttendance(caller, classId, targetDate);
    }

    /**
     * Parent views child's attendance history and rate summary.
     */
    @Transactional(readOnly = true)
    public AttendanceDto.ChildAttendanceResponse getChildAttendance(Principals.Parent parent, String childId, LocalDate from, LocalDate to) {
        ChildEntity child = requireParentChild(parent, childId);

        LocalDate startDate = from != null ? from : LocalDate.now().minusMonths(1);
        LocalDate endDate = to != null ? to : LocalDate.now();

        List<AttendanceEntity> entities = attendance.findByChildIdAndDateBetweenOrderByDateDesc(child.getId(), startDate, endDate);
        List<AttendanceDto.ChildAttendanceRecord> records = entities.stream()
                .map(e -> new AttendanceDto.ChildAttendanceRecord(e.getDate(), e.getStatus(), e.getNotes()))
                .toList();

        int present = 0, absent = 0, late = 0, excused = 0;
        for (AttendanceEntity e : entities) {
            switch (e.getStatus()) {
                case "PRESENT" -> present++;
                case "ABSENT" -> absent++;
                case "LATE" -> late++;
                case "EXCUSED" -> excused++;
            }
        }
        int total = entities.size();
        double rate = total > 0 ? ((double) (present + late) / total) * 100.0 : 100.0;
        double roundedRate = Math.round(rate * 10.0) / 10.0;

        AttendanceDto.ChildAttendanceSummary summary = new AttendanceDto.ChildAttendanceSummary(
                total, present, absent, late, excused, roundedRate
        );

        return new AttendanceDto.ChildAttendanceResponse(records, summary);
    }

    /**
     * Parent views today's attendance record for child.
     */
    @Transactional(readOnly = true)
    public AttendanceDto.ChildAttendanceRecord getTodayAttendance(Principals.Parent parent, String childId) {
        ChildEntity child = requireParentChild(parent, childId);
        return attendance.findByChildIdAndDate(child.getId(), LocalDate.now())
                .map(e -> new AttendanceDto.ChildAttendanceRecord(e.getDate(), e.getStatus(), e.getNotes()))
                .orElse(null);
    }

    private ChildEntity requireParentChild(Principals.Parent parent, String childId) {
        if (parent == null) throw ApiException.unauthorized("Sign in first.");
        ChildEntity child = children.findOneById(childId).filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("child"));
        if (!parent.parentId().equals(child.getParentId())) {
            throw ApiException.notFound("child");
        }
        return child;
    }
}
