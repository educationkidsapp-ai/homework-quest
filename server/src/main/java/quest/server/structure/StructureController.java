package quest.server.structure;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.TenantContext;

/**
 * Feature 36: the Admin's "هيكل المدرسة / School structure" — the academies with their settings, the one visual tree,
 * the drag that moves a teacher, and the three bulk tools.
 *
 * <p><strong>The Admin alone</strong>, on every route: `structure.manage` is hers and nobody else's, and the other
 * roles keep the read-only views they already have. Behind `schoolStructure`, which is seeded on (D30), so a school
 * that switches it off answers 404 here and loses nothing — an academy without a grading scheme reads exactly
 * today's four bands ({@link GradingSchemes#DEFAULT}).
 *
 * <p>The school is always the caller's own scope ({@link TenantContext#writeSchoolId}), never a parameter: an Admin
 * picks one with `X-School-Id` like everywhere else, and another school's structure is invisible.
 */
@RestController
@FeatureFlag(FlagKeys.SCHOOL_STRUCTURE)
@Tag(name = "School structure", description = "The Admin's academies, the structure tree and the bulk tools")
public class StructureController {
    private final AcademyService academies; private final StructureTree tree; private final StructureBulk bulk;
    private final TenantContext tenant;

    public StructureController(AcademyService academies, StructureTree tree, StructureBulk bulk, TenantContext tenant) {
        this.academies = academies; this.tree = tree; this.bulk = bulk; this.tenant = tenant;
    }

    // ---------------------------------------------------------------- the tree

    @GetMapping(value = "/admin/structure", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('structure.manage')")
    public StructureDto.StructureTreeView structure() { return tree.tree(school()); }

    // ---------------------------------------------------------------- academies

    @GetMapping(value = "/admin/structure/academies", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('structure.manage')")
    public List<StructureDto.Academy> structureAcademies() { return academies.academies(school()); }

    @PutMapping(value = "/admin/structure/academies/{key}", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('structure.manage')")
    public StructureDto.Academy updateStructureAcademy(@PathVariable String key,
                                                       @RequestBody StructureDto.UpdateAcademyRequest body) {
        return academies.update(school(), key, body);
    }

    @PutMapping(value = "/admin/structure/academies/{key}/subjects", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('structure.manage')")
    public List<StructureDto.AcademySubject> saveStructureSubjects(@PathVariable String key,
                                                                   @RequestBody StructureDto.SaveSubjectsRequest body) {
        return academies.saveSubjects(school(), key, body);
    }

    @PutMapping(value = "/admin/structure/academies/{key}/terms", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('structure.manage')")
    public List<StructureDto.AcademyTerm> saveStructureTerms(@PathVariable String key,
                                                             @RequestBody StructureDto.SaveTermsRequest body) {
        return academies.saveTerms(school(), key, body);
    }

    @PutMapping(value = "/admin/structure/academies/{key}/grading-scheme", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('structure.manage')")
    public StructureDto.GradingScheme saveStructureScheme(@PathVariable String key,
                                                          @RequestBody StructureDto.SaveSchemeRequest body) {
        return academies.saveScheme(school(), key, body);
    }

    // ---------------------------------------------------------------- the drag

    @PutMapping(value = "/admin/structure/assignments", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('structure.manage')")
    public StructureDto.MoveAssignmentResult moveStructureAssignment(@RequestBody StructureDto.MoveAssignmentRequest body) {
        return bulk.moveAssignment(school(), body);
    }

    // ---------------------------------------------------------------- the bulk tools

    @PostMapping(value = "/admin/structure/next-year/preview", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('structure.manage')")
    public StructureDto.NextYearPreview previewStructureNextYear(@RequestBody StructureDto.NextYearRequest body) {
        return bulk.nextYearPreview(school(), body);
    }

    @PostMapping(value = "/admin/structure/next-year/apply", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('structure.manage')")
    public StructureDto.NextYearPreview applyStructureNextYear(@RequestBody StructureDto.NextYearRequest body) {
        return bulk.nextYearApply(school(), body);
    }

    @PutMapping(value = "/admin/structure/grade-names", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('structure.manage')")
    public StructureDto.GradeNamesResult renameStructureGrades(@RequestBody StructureDto.GradeNamesRequest body) {
        return bulk.gradeNames(school(), body);
    }

    @PostMapping(value = "/admin/structure/classes/{id}/move/preview", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('structure.manage')")
    public StructureDto.MoveClassPreview previewStructureClassMove(@PathVariable String id,
                                                                   @RequestBody StructureDto.MoveClassRequest body) {
        return bulk.movePreview(school(), id, body);
    }

    @PostMapping(value = "/admin/structure/classes/{id}/move/apply", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('structure.manage')")
    public StructureDto.MoveClassPreview applyStructureClassMove(@PathVariable String id,
                                                                 @RequestBody StructureDto.MoveClassRequest body,
                                                                 @RequestParam(defaultValue = "false") boolean confirm) {
        return bulk.moveApply(school(), id, body, confirm);
    }

    private String school() { return tenant.writeSchoolId(); }
}
