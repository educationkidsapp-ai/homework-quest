package quest.server.structure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.config.ApiException;
import quest.server.structure.Entities.AcademyEntity;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.ClassService;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.TeacherScope;

/**
 * Feature 36's three bulk tools, each of which <strong>previews before it writes</strong>: next year's classes, an
 * academy's grade names, and moving a class to another academy.
 *
 * <ul>
 *   <li><strong>Next year.</strong> Each class moves up one grade with the same name pattern ("1A" becomes "2A"), the
 *       children optionally carried over, and <strong>the teachers not carried over</strong> — a new year's
 *       assignments are a decision, not a copy. A class in the academy's top grade has nowhere to go and is listed as
 *       graduating.</li>
 *   <li><strong>Grade names.</strong> The academy's own naming, applied in one go; the preview is the list of labels
 *       it returns.</li>
 *   <li><strong>Move a class.</strong> The preview lists everything that changes, in the spec's order: the class's
 *       curriculum, its children's academy, the assignments whose subject the target academy does not offer, the
 *       director and coordinators who will see it, and its grading scheme. A move that would show a released result
 *       in a different band answers 409 `confirm_released` until `confirm=true` is sent.</li>
 * </ul>
 *
 * <p>Assignments are moved and removed through {@link TeacherScope}, which is the only place allowed to touch
 * `teaching_assignments` (`TeacherScopeArchitectureTest`).
 */
@Service
public class StructureBulk {
    private final AcademyService academyService; private final StructureTree tree; private final ClassRepository classes;
    private final ClassService sections; private final TeacherScope scope;
    private final quest.server.children.ChildRepository children; private final GradingSchemes schemes;
    private final ReleasedResults released;

    public StructureBulk(AcademyService academyService, StructureTree tree, ClassRepository classes,
                         ClassService sections, TeacherScope scope,
                         quest.server.children.ChildRepository children, GradingSchemes schemes,
                         ReleasedResults released) {
        this.academyService = academyService; this.tree = tree; this.classes = classes; this.sections = sections;
        this.scope = scope; this.children = children; this.schemes = schemes; this.released = released;
    }

    // ---------------------------------------------------------------- moving a teacher (the drag)

    /**
     * `PUT /admin/structure/assignments`. The teacher is whoever holds the source slot, so the request cannot move
     * somebody else by accident. Dropping on a slot another teacher already holds answers 409 `replace_required`
     * naming her, unless `replace: true` — which is the dialog the spec asks for, and the second teacher's
     * assignment is then taken away.
     */
    @Transactional
    public StructureDto.MoveAssignmentResult moveAssignment(String schoolId, StructureDto.MoveAssignmentRequest body) {
        if (body == null || body.fromClassId() == null || body.fromSubject() == null
                || body.toClassId() == null || body.toSubject() == null)
            throw ApiException.badRequest("fromClassId, fromSubject, toClassId and toSubject are all required");
        var from = tree.section(body.fromClassId());
        var to = tree.section(body.toClassId());
        String fromSubject = AcademyService.key(body.fromSubject()), toSubject = AcademyService.key(body.toSubject());
        var source = scope.assignmentOn(from.getId(), fromSubject)
                .orElseThrow(() -> ApiException.notFound("assignment"));
        if (!academyService.offers(schoolId, AcademyService.key(to.getCurriculum()), toSubject))
            throw ApiException.badRequest(to.getName() + "'s academy does not offer " + toSubject + ".");
        if (from.getId().equals(to.getId()) && fromSubject.equals(toSubject))
            throw ApiException.badRequest("that assignment is already there");

        StructureDto.StructureStaffRef replaced = null;
        var holder = scope.assignmentOn(to.getId(), toSubject).orElse(null);
        if (holder != null && !holder.getId().equals(source.getId())) {
            if (!Boolean.TRUE.equals(body.replace())) {
                var who = academyService.staff(schoolId, holder.getTeacherId());
                throw ApiException.conflict("replace_required", to.getName() + " · " + toSubject + " is taught by "
                        + (who == null ? "another teacher" : who.name()) + ". Send replace=true to replace her.");
            }
            replaced = academyService.staff(schoolId, holder.getTeacherId());
            scope.removeAssignment(holder);
        }
        var moved = scope.moveAssignment(source, to.getId(), toSubject);
        return new StructureDto.MoveAssignmentResult(to.getId(), toSubject,
                academyService.staff(schoolId, moved.getTeacherId()), replaced);
    }

    // ---------------------------------------------------------------- next year's classes

    @Transactional
    public StructureDto.NextYearPreview nextYearPreview(String schoolId, StructureDto.NextYearRequest body) {
        var academy = academy(schoolId, body == null ? null : body.academyKey());
        boolean carry = body != null && Boolean.TRUE.equals(body.carryChildren());
        var grades = tree.gradesOf(academy.getAcademyKey());
        var roster = rosterSizes(schoolId);
        var rows = new ArrayList<StructureDto.NextYearClass>();
        int created = 0, moved = 0, graduating = 0;
        for (var section : sectionsOf(schoolId, academy.getAcademyKey())) {
            int next = section.getGrade() + 1;
            int here = roster.getOrDefault(section.getId(), 0);
            boolean last = !grades.contains(next);
            if (last) {
                graduating++;
                rows.add(new StructureDto.NextYearClass(section.getId(), section.getName(), section.getGrade(), null,
                        null, null, here, true));
                continue;
            }
            created++;
            if (carry) moved += here;
            rows.add(new StructureDto.NextYearClass(section.getId(), nextName(section.getName(), section.getGrade(), next),
                    section.getGrade(), next, AcademyService.labelEn(academy, next), AcademyService.labelAr(academy, next),
                    here, false));
        }
        return new StructureDto.NextYearPreview(academy.getAcademyKey(), carry, List.copyOf(rows), created, moved, graduating);
    }

    /** The same list, written: one section per non-graduating class, and the children only when asked for. */
    @Transactional
    public StructureDto.NextYearPreview nextYearApply(String schoolId, StructureDto.NextYearRequest body) {
        var preview = nextYearPreview(schoolId, body);
        var academy = academy(schoolId, preview.academyKey());
        // Top grade first. A class moving up lands on next year's section of the grade above it, which is itself a
        // source in this very list: processed upwards, grade 1's children would be carried on into grade 2's move
        // and end two years ahead of themselves.
        for (var row : preview.classes().stream()
                .sorted(java.util.Comparator.comparingInt(StructureDto.NextYearClass::fromGrade).reversed()).toList()) {
            if (row.graduating()) continue;
            var target = classes.findFirstBySchoolIdAndCurriculumAndGradeAndNameIgnoreCase(
                            schoolId, academy.getAcademyKey(), row.toGrade(), row.name())
                    .orElseGet(() -> sections.create(schoolId, academy.getAcademyKey(), row.toGrade(), row.name()));
            if (!preview.carryChildren()) continue;
            for (var child : children.findByClassIdAndDeletedAtIsNullOrderByNameAsc(row.fromClassId())) {
                child.setClassId(target.getId()); child.setGrade(row.toGrade());
                child.setCurriculum(academy.getAcademyKey());
                children.save(child);
            }
        }
        return preview;
    }

    /** "1A" at grade 1 becomes "2A" at grade 2; a name that does not start with its grade is kept as it is. */
    static String nextName(String name, int fromGrade, int toGrade) {
        String prefix = String.valueOf(fromGrade);
        return name != null && name.startsWith(prefix) ? toGrade + name.substring(prefix.length()) : name;
    }

    // ---------------------------------------------------------------- the grade names

    @Transactional
    public StructureDto.GradeNamesResult gradeNames(String schoolId, StructureDto.GradeNamesRequest body) {
        var academy = academy(schoolId, body == null ? null : body.academyKey());
        academyService.applyNaming(academy, body.style(), body.offset(), body.wordEn(), body.wordAr());
        academy.setUpdatedAt(java.time.Instant.now());
        tree.academies().save(academy);
        return new StructureDto.GradeNamesResult(academy.getAcademyKey(), AcademyService.naming(academy), tree.labels(academy));
    }

    // ---------------------------------------------------------------- moving a class to another academy

    @Transactional
    public StructureDto.MoveClassPreview movePreview(String schoolId, String classId, StructureDto.MoveClassRequest body) {
        var section = tree.section(classId);
        var target = academy(schoolId, body == null ? null : body.toAcademyKey());
        String fromKey = AcademyService.key(section.getCurriculum());
        if (fromKey.equals(target.getAcademyKey()))
            throw ApiException.badRequest(section.getName() + " is already in " + target.getNameEn() + ".");
        var source = academyService.require(schoolId, fromKey);

        var taught = scope.assignmentsOn(section.getId());
        var dropped = new ArrayList<StructureDto.StructureSubjectSlot>();
        var subjects = new ArrayList<String>();
        for (var assignment : taught) {
            String subject = AcademyService.key(assignment.getSubject());
            subjects.add(subject);
            if (!academyService.offers(schoolId, target.getAcademyKey(), subject))
                dropped.add(new StructureDto.StructureSubjectSlot(subject, subject, subject,
                        academyService.staff(schoolId, assignment.getTeacherId()), List.of()));
        }
        var before = schemes.of(schoolId, fromKey);
        var after = schemes.of(schoolId, target.getAcademyKey());
        var count = released.of(section, before, after);
        return new StructureDto.MoveClassPreview(section.getId(), section.getName(), fromKey, target.getAcademyKey(),
                children.findByClassIdAndDeletedAtIsNullOrderByNameAsc(section.getId()).size(), List.copyOf(dropped),
                academyService.staff(schoolId, source.getDirectorId()),
                academyService.staff(schoolId, target.getDirectorId()),
                tree.coordinatorsOf(schoolId, fromKey, subjects),
                tree.coordinatorsOf(schoolId, target.getAcademyKey(), subjects),
                before.view(), after.view(), count.released(), count.rebanded());
    }

    /**
     * The move itself. Everything the preview listed happens together: the class's curriculum, its children's
     * academy, and the assignments the target academy has no subject for. The scores are untouched — only the band
     * they are shown in — which is why a rebanded released result needs `confirm=true` first.
     */
    @Transactional
    public StructureDto.MoveClassPreview moveApply(String schoolId, String classId, StructureDto.MoveClassRequest body,
                                                   boolean confirm) {
        var preview = movePreview(schoolId, classId, body);
        if (preview.rebandedResults() > 0 && !confirm)
            throw ReleasedResults.confirmReleased(new ReleasedResults.Count(preview.releasedResults(), preview.rebandedResults()));
        var section = tree.section(classId);
        for (var assignment : scope.assignmentsOn(section.getId()))
            if (!academyService.offers(schoolId, preview.toAcademyKey(), AcademyService.key(assignment.getSubject())))
                scope.removeAssignment(assignment);
        section.setCurriculum(preview.toAcademyKey());
        classes.save(section);
        for (var child : children.findByClassIdAndDeletedAtIsNullOrderByNameAsc(section.getId())) {
            child.setCurriculum(preview.toAcademyKey());
            children.save(child);
        }
        return new StructureDto.MoveClassPreview(preview.classId(), preview.name(), preview.fromAcademyKey(),
                preview.toAcademyKey(), preview.children(), preview.droppedAssignments(), preview.fromDirector(),
                preview.toDirector(), preview.fromCoordinators(), preview.toCoordinators(), preview.fromScheme(),
                preview.toScheme(), preview.releasedResults(), preview.rebandedResults());
    }

    // ---------------------------------------------------------------- helpers

    private AcademyEntity academy(String schoolId, String academyKey) {
        if (academyKey == null || academyKey.isBlank()) throw ApiException.badRequest("academyKey is required");
        academyService.ensure(schoolId);
        return academyService.require(schoolId, academyKey);
    }

    private List<ClassEntity> sectionsOf(String schoolId, String academyKey) {
        return classes.findBySchoolIdAndNameIsNotNullOrderByCurriculumAscGradeAscNameAsc(schoolId).stream()
                .filter(k -> academyKey.equals(AcademyService.key(k.getCurriculum()))).toList();
    }

    private java.util.Map<String, Integer> rosterSizes(String schoolId) {
        var out = new LinkedHashMap<String, Integer>();
        for (var child : children.findBySchoolIdAndDeletedAtIsNullOrderByNameAsc(schoolId))
            if (child.getClassId() != null) out.merge(child.getClassId(), 1, Integer::sum);
        return out;
    }
}
