package quest.server.structure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.config.ApiException;
import quest.server.content.CourseRepository;
import quest.server.structure.Entities.AcademyEntity;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;
import quest.server.tenancy.FloorScope;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TeacherScope;

/**
 * Feature 36's one visual tree: <strong>academy → grades → classes → teachers per subject</strong>, with the floor
 * supervisor shown on each class on her floor and the coordinators shown on the subjects they cover.
 *
 * <p><strong>Where every node comes from.</strong> The academies are {@link AcademyService}'s rows; the grades are
 * the `courses` of that curriculum, so a grade with no class is still drawn (the spec asks for the empty spots);
 * the classes are the school's sections; the teachers per subject are the `teaching_assignments` of the school, read
 * through {@link TeacherScope#assignmentsIn} because that is the only place allowed to touch that table
 * (`TeacherScopeArchitectureTest`); the coordinators are `staff_scopes` rows with a subject; and the floor
 * supervisors come from {@link FloorScope}, asked only when the `floorSupervisor` switch is on for the school —
 * feature 20 is a feature of its own and a school without it has no floors to draw.
 *
 * <p>Fixed in the number of statements: the academies, the courses, the sections, the assignments, the scope rows,
 * the rosters and the users, whatever the size of the school.
 */
@Service
public class StructureTree {
    private final AcademyService academyService; private final AcademyRepository academies;
    private final CourseRepository courses; private final ClassRepository classes;
    private final TeacherScope scope; private final StaffScopeRepository scopes; private final FloorScope floors;
    private final quest.server.children.ChildRepository children;
    private final quest.server.flags.FeatureFlags flags;

    public StructureTree(AcademyService academyService, AcademyRepository academies, CourseRepository courses,
                         ClassRepository classes, TeacherScope scope, StaffScopeRepository scopes, FloorScope floors,
                         quest.server.children.ChildRepository children, quest.server.flags.FeatureFlags flags) {
        this.academyService = academyService; this.academies = academies; this.courses = courses;
        this.classes = classes; this.scope = scope; this.scopes = scopes; this.floors = floors;
        this.children = children; this.flags = flags;
    }

    @Transactional
    public StructureDto.StructureTreeView tree(String schoolId) {
        var rows = academyService.ensure(schoolId);
        var sections = classes.findBySchoolIdAndNameIsNotNullOrderByCurriculumAscGradeAscNameAsc(schoolId);
        var assignments = scope.assignmentsIn(schoolId);
        var byClass = new LinkedHashMap<String, List<TeachingAssignmentEntity>>();
        for (var a : assignments) byClass.computeIfAbsent(a.getClassId(), k -> new ArrayList<>()).add(a);
        var roster = new LinkedHashMap<String, Integer>();
        for (var child : children.findBySchoolIdAndDeletedAtIsNullOrderByNameAsc(schoolId))
            if (child.getClassId() != null) roster.merge(child.getClassId(), 1, Integer::sum);
        var coordinators = coordinatorsBySubject(schoolId);
        boolean withFloors = flags.isOn(schoolId, quest.server.flags.FlagKeys.FLOOR_SUPERVISOR);

        var out = new ArrayList<StructureDto.AcademyNode>(rows.size());
        for (var academy : rows) {
            var offered = academyService.subjects(schoolId, academy.getAcademyKey());
            var grades = new ArrayList<StructureDto.GradeNode>();
            for (int grade : gradesOf(academy.getAcademyKey())) {
                var nodes = new ArrayList<StructureDto.ClassNode>();
                for (var section : sections) {
                    if (!academy.getAcademyKey().equals(AcademyService.key(section.getCurriculum())) || section.getGrade() != grade) continue;
                    nodes.add(classNode(schoolId, section, offered, byClass.getOrDefault(section.getId(), List.of()),
                            coordinators, roster.getOrDefault(section.getId(), 0), withFloors));
                }
                grades.add(new StructureDto.GradeNode(grade, AcademyService.labelEn(academy, grade),
                        AcademyService.labelAr(academy, grade), List.copyOf(nodes)));
            }
            out.add(new StructureDto.AcademyNode(academyService.view(schoolId, academy), List.copyOf(grades)));
        }
        return new StructureDto.StructureTreeView(List.copyOf(out));
    }

    /**
     * One class with a slot per subject the academy offers — a slot whose `teacher` is null is a subject nobody
     * teaches, which is one of the three empty spots the spec asks to be visible. A subject that is taught but is
     * not on the academy's list is still drawn, after the offered ones: hiding a real assignment would hide the very
     * thing a class move is about.
     */
    private StructureDto.ClassNode classNode(String schoolId, ClassEntity section,
                                             List<StructureDto.AcademySubject> offered,
                                             List<TeachingAssignmentEntity> assignments,
                                             Map<String, List<StructureDto.StaffRef>> coordinators,
                                             int children, boolean withFloors) {
        var bySubject = new LinkedHashMap<String, TeachingAssignmentEntity>();
        for (var a : assignments) bySubject.putIfAbsent(AcademyService.key(a.getSubject()), a);
        var slots = new ArrayList<StructureDto.SubjectSlot>();
        var seen = new LinkedHashSet<String>();
        for (var subject : offered) {
            seen.add(subject.subject());
            slots.add(slot(schoolId, subject.subject(), subject.nameEn(), subject.nameAr(),
                    bySubject.get(subject.subject()), coordinators));
        }
        for (var entry : bySubject.entrySet())
            if (seen.add(entry.getKey()))
                slots.add(slot(schoolId, entry.getKey(), entry.getKey(), entry.getKey(), entry.getValue(), coordinators));
        var supervisors = new ArrayList<StructureDto.StaffRef>();
        if (withFloors)
            for (String userId : floors.supervisorsOfSection(schoolId, section.getId())) {
                var ref = academyService.staff(schoolId, userId);
                if (ref != null) supervisors.add(ref);
            }
        return new StructureDto.ClassNode(section.getId(), section.getName(), section.getGrade(), children,
                List.copyOf(supervisors), List.copyOf(slots));
    }

    private StructureDto.SubjectSlot slot(String schoolId, String subject, String nameEn, String nameAr,
                                          TeachingAssignmentEntity assignment,
                                          Map<String, List<StructureDto.StaffRef>> coordinators) {
        return new StructureDto.SubjectSlot(subject, nameEn, nameAr,
                assignment == null ? null : academyService.staff(schoolId, assignment.getTeacherId()),
                coordinators.getOrDefault(subject, List.of()));
    }

    /** The coordinators per subject: a `staff_scopes` row with a subject, whose `curriculum` null means both. */
    @Transactional(readOnly = true)
    public Map<String, List<StructureDto.StaffRef>> coordinatorsBySubject(String schoolId) {
        var out = new LinkedHashMap<String, List<StructureDto.StaffRef>>();
        for (var row : scopes.findBySchoolIdOrderByUserIdAsc(schoolId)) {
            if (row.getSubject() == null) continue;
            var ref = academyService.staff(schoolId, row.getUserId());
            if (ref == null) continue;
            out.computeIfAbsent(AcademyService.key(row.getSubject()), k -> new ArrayList<>()).add(ref);
        }
        return out;
    }

    /** The coordinators of one academy, for a class move's "who will see it" list. */
    @Transactional(readOnly = true)
    public List<StructureDto.StaffRef> coordinatorsOf(String schoolId, String academyKey, List<String> subjects) {
        var out = new ArrayList<StructureDto.StaffRef>();
        for (var row : scopes.findBySchoolIdOrderByUserIdAsc(schoolId)) {
            if (row.getSubject() == null) continue;
            if (row.getCurriculum() != null && !academyKey.equals(AcademyService.key(row.getCurriculum()))) continue;
            if (!subjects.contains(AcademyService.key(row.getSubject()))) continue;
            var ref = academyService.staff(schoolId, row.getUserId());
            if (ref != null && out.stream().noneMatch(s -> s.userId().equals(ref.userId()))) out.add(ref);
        }
        return List.copyOf(out);
    }

    /** The grades an academy has: the `courses` of its curriculum key, which is where a grade exists at all. */
    @Transactional(readOnly = true)
    public List<Integer> gradesOf(String academyKey) {
        return courses.findAll().stream().filter(c -> academyKey.equals(AcademyService.key(c.getCurriculum())))
                .map(quest.server.content.Entities.CourseEntity::getGrade).distinct().sorted().toList();
    }

    /** Every grade label of one academy, which the "rename grades" dialog lists before and after. */
    @Transactional(readOnly = true)
    public List<StructureDto.GradeLabel> labels(AcademyEntity academy) {
        return gradesOf(academy.getAcademyKey()).stream()
                .map(g -> new StructureDto.GradeLabel(g, AcademyService.labelEn(academy, g), AcademyService.labelAr(academy, g)))
                .toList();
    }

    /** A section of this school, or 404 — never a pre-V7 leftover, exactly as {@link TeacherScope#section} rules. */
    @Transactional(readOnly = true)
    public ClassEntity section(String classId) {
        return classes.findOneById(classId).filter(ClassEntity::isSection).orElseThrow(() -> ApiException.notFound("class"));
    }

    /** Only so the academies of a school can be resolved by key in one place. */
    AcademyRepository academies() { return academies; }
}
