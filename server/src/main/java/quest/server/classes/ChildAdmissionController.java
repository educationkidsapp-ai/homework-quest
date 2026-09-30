package quest.server.classes;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.tenancy.TeacherScope;

/**
 * The Admin's <strong>Children &amp; parents</strong> page (MA1, the owner's item 5): a child, her class and her
 * parent's login created in one form, and the searchable page of the families that came of it.
 *
 * <p><strong>Why the read is `/admin/children/search` and not `/admin/children`.</strong> That path is taken, by the
 * roster list {@link ClassAdminController} has answered since §3 — a flat array of {@code RosterChild}, which the
 * dashboard's attach picker reads with `?unassigned=true`. A page envelope with parent columns is a different answer
 * to the same question, and changing the old route's shape would break the picker for a screen that is being built
 * beside this one. The edit stays on the existing `PATCH /admin/children/{id}`, which MA1 widens by `parentPhone`.
 *
 * <p><strong>The initial password is in the request and the new one is in the response, and neither is anywhere
 * else</strong> — not in the audit row, not in a log line, not in a mail. Both routes are POSTs, so no proxy or
 * browser history ever holds one in a URL.
 *
 * <p><strong>Carries no {@link quest.server.flags.FeatureFlag}</strong>, and is named in
 * `FeatureFlagCoverageTest.INFRASTRUCTURE` beside {@code TeacherAdminController} for its exact reason: this is how a
 * family comes to exist at all and how a parent locked out of her account is given a new password, so a flag that
 * could switch it off is one nobody could turn back on without a database session.
 */
@RestController
@Tag(name = "Children and parents", description = "Children created together with their parents' accounts")
public class ChildAdmissionController {
    private final ChildAdmissionService admissions;

    public ChildAdmissionController(ChildAdmissionService admissions) { this.admissions = admissions; }

    @GetMapping(value = "/admin/children/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('admin.children.read')")
    public ClassDto.FamilyPage families(@RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "0") int size) {
        return admissions.search(q, page, size);
    }

    /** 201 with the ids that were written, and whether the parent account is new or one the school already had. */
    @PostMapping(value = "/admin/children", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('admin.children.write')")
    public ClassDto.ChildAdmission admitChild(@AuthenticationPrincipal Principals.User caller,
                                              @RequestBody @Valid ClassDto.AdmitChildRequest body) {
        return admissions.admit(TeacherScope.require(caller), body);
    }

    /** A new one-time password for the parent's login; the old one stops working at her next sign-in. */
    @PostMapping(value = "/admin/children/{id}/parent/reset-password", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('admin.children.write')")
    public ClassDto.TemporaryPassword resetParentPassword(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return admissions.resetParentPassword(TeacherScope.require(caller), id);
    }
}
