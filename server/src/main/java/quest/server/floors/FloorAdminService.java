package quest.server.floors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.Curriculum;
import quest.server.auth.AuditService;
import quest.server.auth.AuthService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.children.ChildRepository;
import quest.server.classes.SectionService;
import quest.server.classes.TemporaryPasswords;
import quest.server.config.ApiException;
import quest.server.floors.Entities.FloorClassEntity;
import quest.server.floors.Entities.FloorEntity;
import quest.server.floors.Entities.FloorSupervisorEntity;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.FloorScope;
import quest.server.tenancy.TenantContext;

/**
 * The Admin's Floors page and her Floor supervisors page (feature 20): a floor with its classes and the people who
 * hold it, and the accounts those people sign in with.
 *
 * <p>Shaped after {@link quest.server.coordinator.CoordinatorAdminService} deliberately, because the two are the same
 * job one role apart: the `users` row is written here rather than through {@code UserService} for that class's reason
 * — `POST /admin/users` creates a TEACHER or a MANAGERIAL of one school and nothing else, and widening it would make
 * every caller of it able to mint a role. The temporary password is in the response body and nowhere else
 * ({@link TemporaryPasswords}): not in the audit row, not in a log line, not in a mail, and the route that answers one
 * is a POST so no proxy or browser history holds it in a URL.
 *
 * <p><strong>A class is on at most one floor.</strong> V42's unique index on `(school_id, section_id)` is the
 * backstop; the refusal is raised here, before anything is written, as a {@code 409 class_on_another_floor} naming the
 * floor that already holds it — so a refused Save changes nothing and the Admin is told what to undo.
 *
 * <p><strong>Nothing takes a school from the request</strong>: it is {@link TenantContext#writeSchoolId()}, so an
 * Admin who picked a school with `X-School-Id` acts in that one, and a floor of another school is simply not found.
 */
@Service
public class FloorAdminService {
    /** The academies the contract has (`quest.api.dto.Curriculum`), as the wire spells them. */
    private static final List<String> ACADEMIES = java.util.Arrays.stream(Curriculum.values()).map(c -> c.name().toLowerCase(Locale.ROOT)).toList();

    private final FloorRepository floors; private final FloorClassRepository floorClasses;
    private final FloorSupervisorRepository supervisors; private final ClassRepository classes;
    private final ChildRepository children; private final UserRepository users;
    private final TemporaryPasswords passwords; private final PasswordEncoder encoder;
    private final TenantContext tenant; private final AuditService audit;
    private final quest.server.auth.RefreshTokenService refreshTokens; private final java.time.Clock clock;

    public FloorAdminService(FloorRepository floors, FloorClassRepository floorClasses, FloorSupervisorRepository supervisors,
                             ClassRepository classes, ChildRepository children, UserRepository users,
                             TemporaryPasswords passwords, PasswordEncoder encoder, TenantContext tenant,
                             AuditService audit, quest.server.auth.RefreshTokenService refreshTokens, java.time.Clock clock) {
        this.floors = floors; this.floorClasses = floorClasses; this.supervisors = supervisors; this.classes = classes;
        this.children = children; this.users = users; this.passwords = passwords; this.encoder = encoder;
        this.tenant = tenant; this.audit = audit; this.refreshTokens = refreshTokens; this.clock = clock;
    }

    // ---------------------------------------------------------------- /admin/floors

    /** Every floor of the school with its classes and supervisors: five statements, never one per floor. */
    public List<FloorDto.Floor> list() {
        String schoolId = tenant.writeSchoolId();
        var rows = floors.findBySchoolIdOrderByNameAsc(schoolId);
        if (rows.isEmpty()) return List.of();
        var sections = sections(schoolId); var names = names(schoolId);
        return rows.stream().map(f -> floor(schoolId, f, sections, names)).toList();
    }

    @Transactional
    public FloorDto.Floor create(Principals.User caller, FloorDto.CreateFloorRequest request) {
        String schoolId = tenant.writeSchoolId();
        String name = text(request.name());
        String academy = SectionService.oneOf(request.academy(), ACADEMIES, "academy");
        if (floors.findBySchoolIdOrderByNameAsc(schoolId).stream().anyMatch(f -> f.getName().equalsIgnoreCase(name)))
            throw ApiException.conflict("This school already has a floor called " + name + ".");

        var floor = new FloorEntity();
        floor.setId(UUID.randomUUID().toString()); floor.setSchoolId(schoolId);
        floor.setName(name); floor.setAcademy(academy); floor.setCreatedAt(Instant.now(clock));
        floors.save(floor);
        setClasses(schoolId, floor, request.classIds() == null ? List.of() : request.classIds());
        setSupervisors(schoolId, floor, request.supervisorIds() == null ? List.of() : request.supervisorIds());
        audit.record(caller.userId(), "floor.create", "floor", floor.getId(), schoolId,
                Map.of("name", name, "academy", academy));
        return floor(schoolId, floor, sections(schoolId), names(schoolId));
    }

    /** Only the fields that are present are written; a present `classIds` or `supervisorIds` replaces the whole set. */
    @Transactional
    public FloorDto.Floor update(Principals.User caller, String floorId, FloorDto.UpdateFloorRequest request) {
        String schoolId = tenant.writeSchoolId();
        var floor = floor(floorId);
        if (request.name() != null) {
            String name = text(request.name());
            if (floors.findBySchoolIdOrderByNameAsc(schoolId).stream()
                    .anyMatch(f -> !f.getId().equals(floor.getId()) && f.getName().equalsIgnoreCase(name)))
                throw ApiException.conflict("This school already has a floor called " + name + ".");
            floor.setName(name);
        }
        if (request.academy() != null) floor.setAcademy(SectionService.oneOf(request.academy(), ACADEMIES, "academy"));
        floors.save(floor);
        if (request.classIds() != null) setClasses(schoolId, floor, request.classIds());
        if (request.supervisorIds() != null) setSupervisors(schoolId, floor, request.supervisorIds());
        audit.record(caller.userId(), "floor.update", "floor", floor.getId(), schoolId, Map.of("name", floor.getName()));
        return floor(schoolId, floor, sections(schoolId), names(schoolId));
    }

    /**
     * The floor goes and its two child tables with it. The rows are deleted here rather than left to the migration's
     * `ON DELETE CASCADE`, so the `school` filter is what proves they are this school's — a cascade runs in the
     * database, where there is no filter.
     */
    @Transactional
    public void delete(Principals.User caller, String floorId) {
        String schoolId = tenant.writeSchoolId();
        var floor = floor(floorId);
        floorClasses.deleteAll(floorClasses.findBySchoolIdAndFloorIdOrderBySectionIdAsc(schoolId, floor.getId()));
        supervisors.deleteAll(supervisors.findBySchoolIdAndFloorIdOrderByUserIdAsc(schoolId, floor.getId()));
        floors.delete(floor);
        audit.record(caller.userId(), "floor.delete", "floor", floor.getId(), schoolId, Map.of("name", floor.getName()));
    }

    // ---------------------------------------------------------------- /admin/floor-supervisors

    /** Every floor supervisor of the school with the floors she holds: three statements, never one per person. */
    public List<FloorDto.FloorSupervisorAccount> supervisors() {
        String schoolId = tenant.writeSchoolId();
        var rows = users.findBySchoolIdAndRole(schoolId, FloorScope.ROLE).stream()
                .sorted(Comparator.comparing(SectionService::displayName, String.CASE_INSENSITIVE_ORDER)).toList();
        if (rows.isEmpty()) return List.of();
        var byFloor = byId(floors.findBySchoolIdOrderByNameAsc(schoolId), FloorEntity::getId);
        var mine = new LinkedHashMap<String, List<FloorDto.FloorRef>>();
        for (var row : supervisors.findBySchoolIdAndUserIdInOrderByFloorIdAsc(schoolId, rows.stream().map(UserEntity::getId).toList())) {
            var floor = byFloor.get(row.getFloorId());
            if (floor != null) mine.computeIfAbsent(row.getUserId(), k -> new ArrayList<>()).add(ref(floor));
        }
        return rows.stream().map(u -> account(u, mine.getOrDefault(u.getId(), List.of()))).toList();
    }

    /** 201 with the one and only sight of the password; it cannot be read again. */
    @Transactional
    public FloorDto.FloorSupervisorCreated createSupervisor(Principals.User caller, FloorDto.CreateFloorSupervisorRequest request) {
        String schoolId = tenant.writeSchoolId();
        String fullName = text(request.fullName());
        String email = AuthService.normalise(request.email());
        if (!email.contains("@")) throw ApiException.badRequest("That is not an email address.");
        if (users.findIdByEmailAcrossSchools(email).isPresent()) throw ApiException.conflict("That address already has an account.");
        var wanted = request.floorIds() == null ? List.<FloorEntity>of() : wantedFloors(request.floorIds());
        String temporary = passwords.generate();

        var user = new UserEntity();
        user.setId(UUID.randomUUID().toString()); user.setSchoolId(schoolId); user.setEmail(email);
        user.setRole(FloorScope.ROLE); user.setStatus("active"); user.setMustChangePassword(true);
        user.setDisplayName(fullName); user.setPasswordHash(encoder.encode(temporary));
        user.setPhone(quest.server.platform.Phones.normalise(request.phone(), "phone"));
        user.setCreatedAt(Instant.now(clock)); user.setUpdatedAt(Instant.now(clock));
        users.save(user);
        setFloors(schoolId, user.getId(), wanted);

        // The password is deliberately not in the audit details: the row is readable by every Admin, for ever.
        audit.record(caller.userId(), "floorSupervisor.create", "user", user.getId(), schoolId,
                Map.of("email", email, "floors", wanted.size()));
        return new FloorDto.FloorSupervisorCreated(account(user, wanted.stream().map(FloorAdminService::ref).toList()), temporary);
    }

    /** The rules that matter are {@link quest.server.classes.StaffAccounts}': disabling revokes her refresh tokens. */
    @Transactional
    public FloorDto.FloorSupervisorAccount updateSupervisor(Principals.User caller, String userId,
                                                            FloorDto.UpdateFloorSupervisorRequest request) {
        String schoolId = tenant.writeSchoolId();
        var user = supervisor(userId);
        quest.server.classes.StaffAccounts.apply(caller, user, request.fullName(), request.phone(), request.active(), refreshTokens);
        users.save(user);
        if (request.floorIds() != null) setFloors(schoolId, user.getId(), wantedFloors(request.floorIds()));
        audit.record(caller.userId(), "floorSupervisor.update", "user", user.getId(), schoolId, Map.of("status", user.getStatus()));
        return account(user, held(schoolId, user.getId()));
    }

    /** A new one-time password for an account that already exists; the old one stops working immediately. */
    @Transactional
    public quest.server.classes.ClassDto.TemporaryPassword resetPassword(Principals.User caller, String userId) {
        var user = supervisor(userId);
        String temporary = quest.server.classes.StaffAccounts.reset(user, encoder, passwords, refreshTokens);
        users.save(user);
        audit.record(caller.userId(), "floorSupervisor.resetPassword", "user", user.getId(), user.getSchoolId(), Map.of());
        return new quest.server.classes.ClassDto.TemporaryPassword(temporary);
    }

    // ---------------------------------------------------------------- the sets

    /**
     * The complete set of sections the floor should hold. Validated before anything is written: a section that is not
     * this school's is 404, and one already on another floor is 409 — so a refused Save changes nothing.
     */
    private void setClasses(String schoolId, FloorEntity floor, List<String> classIds) {
        var wanted = new LinkedHashSet<String>();
        var bySectionId = sections(schoolId);
        var floorNames = byId(floors.findBySchoolIdOrderByNameAsc(schoolId), FloorEntity::getId);
        for (String classId : classIds) {
            var section = bySectionId.get(classId);
            if (section == null) throw ApiException.notFound("class");
            if (!wanted.add(classId)) throw ApiException.badRequest(section.getName() + " is named twice.");
            var holder = floorClasses.findBySchoolIdAndSectionId(schoolId, classId).orElse(null);
            if (holder != null && !holder.getFloorId().equals(floor.getId())) {
                var other = floorNames.get(holder.getFloorId());
                throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "class_on_another_floor",
                        section.getName() + " is already on " + (other == null ? "another floor" : other.getName())
                                + ". A class is on one floor only — take it off that one first.");
            }
        }
        // The DELETEs before the INSERTs, and flushed: Hibernate orders inserts first inside a transaction, so a Save
        // that keeps a section the floor already holds would meet the unique index against the row it is replacing.
        floorClasses.deleteAll(floorClasses.findBySchoolIdAndFloorIdOrderBySectionIdAsc(schoolId, floor.getId()));
        floorClasses.flush();
        for (String classId : wanted) {
            var row = new FloorClassEntity();
            row.setId(UUID.randomUUID().toString()); row.setFloorId(floor.getId()); row.setSchoolId(schoolId);
            row.setSectionId(classId); row.setCreatedAt(Instant.now(clock));
            floorClasses.save(row);
        }
    }

    /** The complete set of supervisors of one floor; anybody who is not a FLOOR_SUPERVISOR of this school is 404. */
    private void setSupervisors(String schoolId, FloorEntity floor, List<String> userIds) {
        var wanted = new LinkedHashSet<String>();
        for (String userId : userIds) { supervisor(userId); wanted.add(userId); }
        supervisors.deleteAll(supervisors.findBySchoolIdAndFloorIdOrderByUserIdAsc(schoolId, floor.getId()));
        supervisors.flush();
        for (String userId : wanted) write(schoolId, floor.getId(), userId);
    }

    /** The complete set of floors one supervisor should hold — the same set from her own page. */
    private void setFloors(String schoolId, String userId, List<FloorEntity> wanted) {
        supervisors.deleteAll(supervisors.findBySchoolIdAndUserIdOrderByFloorIdAsc(schoolId, userId));
        supervisors.flush();
        for (var floor : wanted) write(schoolId, floor.getId(), userId);
    }

    private void write(String schoolId, String floorId, String userId) {
        var row = new FloorSupervisorEntity();
        row.setId(UUID.randomUUID().toString()); row.setFloorId(floorId); row.setSchoolId(schoolId);
        row.setUserId(userId); row.setCreatedAt(Instant.now(clock));
        supervisors.save(row);
    }

    // ---------------------------------------------------------------- helpers

    private List<FloorEntity> wantedFloors(List<String> floorIds) {
        var out = new ArrayList<FloorEntity>();
        var seen = new LinkedHashSet<String>();
        for (String floorId : floorIds) if (seen.add(floorId)) out.add(floor(floorId));
        return out;
    }

    /** One floor of the caller's scope; another school's is simply not found, because the lookup is filtered. */
    private FloorEntity floor(String floorId) {
        return floors.findOneById(floorId)
                .filter(f -> java.util.Objects.equals(tenant.writeSchoolId(), f.getSchoolId()))
                .orElseThrow(() -> ApiException.notFound("floor"));
    }

    /** A FLOOR_SUPERVISOR of the caller's scope. `users` is filtered, so another school's is simply not found. */
    private UserEntity supervisor(String userId) {
        return users.findById(userId)
                .filter(u -> FloorScope.ROLE.equals(u.getRole()) && java.util.Objects.equals(tenant.writeSchoolId(), u.getSchoolId()))
                .orElseThrow(() -> ApiException.notFound("floor supervisor"));
    }

    private List<FloorDto.FloorRef> held(String schoolId, String userId) {
        var byFloor = byId(floors.findBySchoolIdOrderByNameAsc(schoolId), FloorEntity::getId);
        return supervisors.findBySchoolIdAndUserIdOrderByFloorIdAsc(schoolId, userId).stream()
                .map(r -> byFloor.get(r.getFloorId())).filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(FloorEntity::getName, String.CASE_INSENSITIVE_ORDER))
                .map(FloorAdminService::ref).toList();
    }

    private FloorDto.Floor floor(String schoolId, FloorEntity floor, Map<String, ClassEntity> sections, Map<String, String> names) {
        var rows = new ArrayList<FloorDto.FloorClass>();
        var sectionIds = floorClasses.findBySchoolIdAndFloorIdOrderBySectionIdAsc(schoolId, floor.getId()).stream()
                .map(FloorClassEntity::getSectionId).toList();
        var counts = counts(sectionIds);
        for (String sectionId : sectionIds) {
            var section = sections.get(sectionId);
            if (section == null) continue;                                      // a retired or pre-V7 row
            rows.add(new FloorDto.FloorClass(section.getId(), section.getName(), section.getCurriculum(), section.getGrade(),
                    counts.getOrDefault(section.getId(), 0), floor.getId(), floor.getName()));
        }
        rows.sort(Comparator.comparing(FloorDto.FloorClass::grade).thenComparing(FloorDto.FloorClass::className, String.CASE_INSENSITIVE_ORDER));
        var people = supervisors.findBySchoolIdAndFloorIdOrderByUserIdAsc(schoolId, floor.getId()).stream()
                .map(r -> new FloorDto.FloorSupervisorRef(r.getUserId(), names.getOrDefault(r.getUserId(), "")))
                .sorted(Comparator.comparing(FloorDto.FloorSupervisorRef::displayName, String.CASE_INSENSITIVE_ORDER)).toList();
        return new FloorDto.Floor(floor.getId(), floor.getName(), floor.getAcademy(), List.copyOf(rows), people);
    }

    private Map<String, Integer> counts(List<String> sectionIds) {
        var out = new LinkedHashMap<String, Integer>();
        if (sectionIds.isEmpty()) return out;
        for (Object[] row : children.countByClassIdIn(sectionIds)) out.put((String) row[0], ((Number) row[1]).intValue());
        return out;
    }

    private Map<String, ClassEntity> sections(String schoolId) {
        return byId(classes.findBySchoolIdAndNameIsNotNullOrderByCurriculumAscGradeAscNameAsc(schoolId), ClassEntity::getId);
    }

    private Map<String, String> names(String schoolId) {
        var out = new LinkedHashMap<String, String>();
        for (var u : users.findBySchoolIdAndRole(schoolId, FloorScope.ROLE)) out.put(u.getId(), SectionService.displayName(u));
        return out;
    }

    private static <T> Map<String, T> byId(List<T> rows, java.util.function.Function<T, String> id) {
        var out = new LinkedHashMap<String, T>();
        for (T row : rows) out.put(id.apply(row), row);
        return out;
    }

    private static FloorDto.FloorRef ref(FloorEntity floor) {
        return new FloorDto.FloorRef(floor.getId(), floor.getName(), floor.getAcademy());
    }

    private static FloorDto.FloorSupervisorAccount account(UserEntity user, List<FloorDto.FloorRef> floors) {
        return new FloorDto.FloorSupervisorAccount(user.getId(), user.getEmail(), SectionService.displayName(user),
                user.getPhone(), user.getStatus(), floors);
    }

    private static String text(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty() || cleaned.length() > 80) throw ApiException.badRequest("name is 1–80 characters.");
        return cleaned;
    }
}
