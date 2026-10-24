package quest.server.structure;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.structure.Entities.AcademyEntity;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.StaffScopeEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.StaffScopeRepository;

/**
 * Feature 36's academies: the row milestone 06's department key now has, with its names, its descriptive curriculum
 * type, its grade naming, its grading scheme, its terms, its subjects and its director.
 *
 * <p><strong>The academies are the keys already in use.</strong> V58 seeded one per curriculum key each school uses,
 * and {@link #ensure} does the same for a school created after it, so this screen never asks anybody to create an
 * academy and never lets anybody create a key: `quest.api.dto.Curriculum` is a closed enum on the wire and in the
 * released app builds (a third academy is a leftover of this feature, not a setting).
 *
 * <p><strong>The director is the department manager.</strong> Changing her here writes her MANAGERIAL `staff_scopes`
 * row — the row feature 14, the chat and every leaders' board already read — and caches the id on the academy so the
 * tree is one statement. The two are written together or not at all.
 */
@Service
public class AcademyService {

    /** The subject keys this school platform has (`quest.api.dto.Subject`), with a default name for each. */
    private static final Map<String, String[]> SUBJECTS = new LinkedHashMap<>();
    /** The shared Ministry subjects, offered to every academy: Arabic and Islamic studies (`religion`). */
    private static final List<String> MINISTRY = List.of("arabic", "religion");
    static {
        SUBJECTS.put("arabic", new String[] {"Arabic", "اللغة العربية"});
        SUBJECTS.put("religion", new String[] {"Islamic studies", "التربية الإسلامية"});
        SUBJECTS.put("math", new String[] {"Mathematics", "الرياضيات"});
        SUBJECTS.put("english", new String[] {"English", "اللغة الإنجليزية"});
        SUBJECTS.put("science", new String[] {"Science", "العلوم"});
        SUBJECTS.put("french", new String[] {"French", "اللغة الفرنسية"});
    }

    private final AcademyRepository academies; private final AcademySubjectRepository subjectRows;
    private final AcademyTermRepository termRows; private final GradingSchemeRepository schemeRows;
    private final GradingBandRepository bandRows; private final GradingSchemes schemes;
    private final ReleasedResults released; private final SchoolRepository schools; private final ClassRepository classes;
    private final quest.server.children.ChildRepository children; private final StaffScopeRepository scopes;
    private final UserRepository users; private final Json json; private final Clock clock;

    public AcademyService(AcademyRepository academies, AcademySubjectRepository subjectRows,
                          AcademyTermRepository termRows, GradingSchemeRepository schemeRows,
                          GradingBandRepository bandRows, GradingSchemes schemes, ReleasedResults released,
                          SchoolRepository schools, ClassRepository classes,
                          quest.server.children.ChildRepository children, StaffScopeRepository scopes,
                          UserRepository users, Json json, Clock clock) {
        this.academies = academies; this.subjectRows = subjectRows; this.termRows = termRows;
        this.schemeRows = schemeRows; this.bandRows = bandRows; this.schemes = schemes; this.released = released;
        this.schools = schools; this.classes = classes; this.children = children; this.scopes = scopes;
        this.users = users; this.json = json; this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    /**
     * Every academy of the school, creating the rows V58 would have created for a school that did not exist when it
     * ran: one per curriculum key the school's classes, children or `staff_scopes` rows already use, falling back to
     * the school's own `curriculum_options_json` when it has none of those yet.
     */
    @Transactional
    public List<AcademyEntity> ensure(String schoolId) {
        var existing = new LinkedHashMap<String, AcademyEntity>();
        for (var row : academies.findBySchoolIdOrderByAcademyKeyAsc(schoolId)) existing.put(row.getAcademyKey(), row);
        for (String key : keysInUse(schoolId)) {
            if (existing.containsKey(key)) continue;
            existing.put(key, academies.save(seed(schoolId, key)));
        }
        return existing.values().stream().sorted(java.util.Comparator.comparing(AcademyEntity::getAcademyKey)).toList();
    }

    /** The keys a school already uses, in a stable order: its classes, its children, its departments, its options. */
    private List<String> keysInUse(String schoolId) {
        var keys = new java.util.LinkedHashSet<String>();
        for (var section : classes.findBySchoolId(schoolId)) if (section.getCurriculum() != null) keys.add(key(section.getCurriculum()));
        for (var scope : scopes.findBySchoolIdOrderByUserIdAsc(schoolId))
            if (scope.getCurriculum() != null && scope.getSubject() == null) keys.add(key(scope.getCurriculum()));
        for (var child : children.findBySchoolIdAndDeletedAtIsNullOrderByNameAsc(schoolId))
            if (child.getCurriculum() != null) keys.add(key(child.getCurriculum()));
        if (keys.isEmpty()) {
            var school = schools.findById(schoolId).orElse(null);
            if (school != null && school.getCurriculumOptionsJson() != null)
                for (String option : json.strings(school.getCurriculumOptionsJson())) keys.add(key(option));
        }
        return List.copyOf(keys);
    }

    /** V58's own CASE list, in Java, so a school created later reads exactly what a seeded one does. */
    private AcademyEntity seed(String schoolId, String key) {
        boolean british = "british".equals(key);
        var row = new AcademyEntity();
        row.setId(schoolId + ":" + key); row.setSchoolId(schoolId); row.setAcademyKey(key);
        row.setNameEn(british ? "British" : "american".equals(key) ? "American" : key);
        row.setNameAr(british ? "بريطاني" : "american".equals(key) ? "أمريكي" : key);
        row.setCurriculumType(british ? StructureDto.BRITISH : "american".equals(key) ? StructureDto.AMERICAN : StructureDto.OTHER);
        row.setGradeStyle(british ? StructureDto.YEAR : StructureDto.GRADE);
        row.setGradeOffset(british ? 1 : 0);
        row.setGradeWordEn(british ? "Year" : "Grade");
        row.setGradeWordAr(british ? "السنة" : "الصف");
        row.setDirectorId(directorOf(schoolId, key));
        row.setCreatedAt(clock.instant()); row.setUpdatedAt(clock.instant());
        return row;
    }

    /** The MANAGERIAL `staff_scopes` row for a key — feature 14's academy director, if the school has named one. */
    private String directorOf(String schoolId, String key) {
        for (var scope : scopes.findBySchoolIdOrderByUserIdAsc(schoolId))
            if (scope.getSubject() == null && key.equals(key(scope.getCurriculum()))
                    && users.findById(scope.getUserId()).filter(u -> "MANAGERIAL".equals(u.getRole())).isPresent())
                return scope.getUserId();
        return null;
    }

    /**
     * One academy of the school, or 404. {@link #ensure} runs first so that a school whose rows V58 could not seed —
     * one created after the migration — is not a 404 on the very first write the Admin makes.
     */
    @Transactional
    public AcademyEntity require(String schoolId, String academyKey) {
        var found = academies.findBySchoolIdAndAcademyKey(schoolId, key(academyKey));
        if (found.isPresent()) return found.get();
        ensure(schoolId);
        return academies.findBySchoolIdAndAcademyKey(schoolId, key(academyKey))
                .orElseThrow(() -> ApiException.notFound("academy"));
    }

    /** Every academy as the dashboard reads it: settings, naming, scheme, subjects, terms and director. */
    @Transactional
    public List<StructureDto.Academy> academies(String schoolId) {
        return ensure(schoolId).stream().map(row -> view(schoolId, row)).toList();
    }

    public StructureDto.Academy view(String schoolId, AcademyEntity row) {
        return new StructureDto.Academy(row.getAcademyKey(), row.getNameEn(), row.getNameAr(), row.getCurriculumType(),
                naming(row), staff(schoolId, row.getDirectorId()), schemes.of(schoolId, row.getAcademyKey()).view(),
                subjects(schoolId, row.getAcademyKey()), terms(schoolId, row.getAcademyKey()));
    }

    public static StructureDto.GradeNaming naming(AcademyEntity row) {
        return new StructureDto.GradeNaming(row.getGradeStyle(), row.getGradeOffset(), row.getGradeWordEn(), row.getGradeWordAr());
    }

    /** "Year 2" / "السنة 2" for grade 1 in a British academy; "Grade 1" / "الصف 1" in an American one. */
    public static String labelEn(AcademyEntity row, int grade) { return row.getGradeWordEn() + " " + (grade + row.getGradeOffset()); }

    public static String labelAr(AcademyEntity row, int grade) { return row.getGradeWordAr() + " " + (grade + row.getGradeOffset()); }

    /** A member of staff by id, or null — the tree draws an empty spot rather than refusing. */
    public StructureDto.StructureStaffRef staff(String schoolId, String userId) {
        if (userId == null) return null;
        return users.findById(userId).filter(u -> schoolId.equals(u.getSchoolId()))
                .map(u -> new StructureDto.StructureStaffRef(u.getId(), u.getDisplayName() == null ? u.getEmail() : u.getDisplayName(), u.getEmail()))
                .orElse(null);
    }

    // ---------------------------------------------------------------- subjects

    /**
     * The subjects an academy offers. <strong>An academy that has never saved a list offers every subject key</strong>
     * — which is exactly what is true today, before this screen exists, so no assignment anywhere stops validating
     * the moment V58 runs. Once the Admin saves a list, that list is what an assignment is checked against.
     */
    @Transactional(readOnly = true)
    public List<StructureDto.AcademySubject> subjects(String schoolId, String academyKey) {
        var rows = subjectRows.findBySchoolIdAndAcademyKeyOrderByPositionAscSubjectKeyAsc(schoolId, key(academyKey));
        if (!rows.isEmpty())
            return rows.stream().map(r -> new StructureDto.AcademySubject(r.getSubjectKey(), r.getNameEn(), r.getNameAr(), r.isMinistry())).toList();
        return SUBJECTS.entrySet().stream()
                .map(e -> new StructureDto.AcademySubject(e.getKey(), e.getValue()[0], e.getValue()[1], MINISTRY.contains(e.getKey())))
                .toList();
    }

    /** True when the academy offers a subject — the check behind every assignment write and every class move. */
    @Transactional(readOnly = true)
    public boolean offers(String schoolId, String academyKey, String subject) {
        String wanted = key(subject);
        return subjects(schoolId, academyKey).stream().anyMatch(s -> s.subject().equals(wanted));
    }

    @Transactional
    public List<StructureDto.AcademySubject> saveSubjects(String schoolId, String academyKey, StructureDto.SaveSubjectsRequest body) {
        var academy = require(schoolId, academyKey);
        if (body == null || body.subjects() == null || body.subjects().isEmpty())
            throw ApiException.badRequest("an academy offers at least one subject");
        var seen = new java.util.LinkedHashSet<String>();
        var fresh = new ArrayList<Entities.AcademySubjectEntity>();
        int position = 0;
        for (var sent : body.subjects()) {
            String subject = key(sent.subject());
            if (!SUBJECTS.containsKey(subject))
                throw ApiException.badRequest(sent.subject() + " is not one of this platform's subjects (" + String.join(", ", SUBJECTS.keySet()) + ")");
            if (!seen.add(subject)) throw ApiException.badRequest("the subject " + subject + " is listed twice");
            var row = new Entities.AcademySubjectEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId);
            row.setAcademyKey(academy.getAcademyKey()); row.setSubjectKey(subject);
            row.setNameEn(blank(sent.nameEn()) ? SUBJECTS.get(subject)[0] : sent.nameEn().trim());
            row.setNameAr(blank(sent.nameAr()) ? SUBJECTS.get(subject)[1] : sent.nameAr().trim());
            row.setMinistry(sent.ministry()); row.setPosition(position++); row.setCreatedAt(clock.instant());
            fresh.add(row);
        }
        subjectRows.deleteAll(subjectRows.findBySchoolIdAndAcademyKeyOrderByPositionAscSubjectKeyAsc(schoolId, academy.getAcademyKey()));
        subjectRows.saveAll(fresh);
        return subjects(schoolId, academy.getAcademyKey());
    }

    // ---------------------------------------------------------------- terms

    @Transactional(readOnly = true)
    public List<StructureDto.AcademyTerm> terms(String schoolId, String academyKey) {
        return termRows.findBySchoolIdAndAcademyKeyOrderBySchoolYearAscStartsOnAsc(schoolId, key(academyKey)).stream()
                .map(r -> new StructureDto.AcademyTerm(r.getId(), r.getSchoolYear(), r.getNameEn(), r.getNameAr(),
                        r.getStartsOn().toString(), r.getEndsOn().toString()))
                .toList();
    }

    /** The whole list of one academy's terms at once: the dates must be valid and must not overlap inside a year. */
    @Transactional
    public List<StructureDto.AcademyTerm> saveTerms(String schoolId, String academyKey, StructureDto.SaveTermsRequest body) {
        var academy = require(schoolId, academyKey);
        var sent = body == null || body.terms() == null ? List.<StructureDto.AcademyTerm>of() : body.terms();
        var fresh = new ArrayList<Entities.AcademyTermEntity>();
        for (var term : sent) {
            if (blank(term.schoolYear())) throw ApiException.badRequest("a term belongs to a school year");
            if (blank(term.nameEn()) || blank(term.nameAr())) throw ApiException.badRequest("a term needs a name in English and in Arabic");
            LocalDate starts = date(term.startsOn(), "startsOn"), ends = date(term.endsOn(), "endsOn");
            if (ends.isBefore(starts)) throw ApiException.badRequest("a term cannot end before it starts");
            for (var other : fresh)
                if (other.getSchoolYear().equals(term.schoolYear().trim())
                        && !starts.isAfter(other.getEndsOn()) && !ends.isBefore(other.getStartsOn()))
                    throw ApiException.conflict("overlapping_terms",
                            "\"" + term.nameEn() + "\" overlaps \"" + other.getNameEn() + "\" in " + term.schoolYear() + ".");
            var row = new Entities.AcademyTermEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId);
            row.setAcademyKey(academy.getAcademyKey()); row.setSchoolYear(term.schoolYear().trim());
            row.setNameEn(term.nameEn().trim()); row.setNameAr(term.nameAr().trim());
            row.setStartsOn(starts); row.setEndsOn(ends); row.setCreatedAt(clock.instant());
            fresh.add(row);
        }
        termRows.deleteAll(termRows.findBySchoolIdAndAcademyKeyOrderBySchoolYearAscStartsOnAsc(schoolId, academy.getAcademyKey()));
        termRows.saveAll(fresh);
        return terms(schoolId, academy.getAcademyKey());
    }

    // ---------------------------------------------------------------- the grading scheme

    /**
     * One academy's scheme. A change that would show a released result in a different band answers 409
     * `confirm_released` with the count, until `confirm=true` is sent — the spec's own guardrail.
     */
    @Transactional
    public StructureDto.GradingScheme saveScheme(String schoolId, String academyKey, StructureDto.SaveSchemeRequest body) {
        var academy = require(schoolId, academyKey);
        if (body == null || body.kind() == null || !StructureDto.SCHEME_KINDS.contains(body.kind()))
            throw ApiException.badRequest("kind is one of " + StructureDto.SCHEME_KINDS);
        var bands = GradingSchemes.validate(body.bands());
        var before = schemes.of(schoolId, academy.getAcademyKey());
        var after = new GradingSchemes.Scheme(academy.getAcademyKey(), body.kind(), true, bands);
        if (!Boolean.TRUE.equals(body.confirm())) {
            var count = released.ofAcademy(schoolId, academy.getAcademyKey(), before, after);
            if (count.any()) throw ReleasedResults.confirmReleased(count);
        }
        var row = schemeRows.findBySchoolIdAndAcademyKey(schoolId, academy.getAcademyKey()).orElseGet(() -> {
            var created = new Entities.GradingSchemeEntity();
            created.setId(UUID.randomUUID().toString()); created.setSchoolId(schoolId);
            created.setAcademyKey(academy.getAcademyKey()); created.setCreatedAt(clock.instant());
            return created;
        });
        row.setKind(body.kind()); row.setUpdatedAt(clock.instant());
        schemeRows.save(row);
        bandRows.deleteAll(bandRows.findBySchoolIdAndSchemeIdOrderByLowerBoundAsc(schoolId, row.getId()));
        int position = 0;
        for (var band : bands) {
            var entity = new Entities.GradingBandEntity();
            entity.setId(UUID.randomUUID().toString()); entity.setSchoolId(schoolId); entity.setSchemeId(row.getId());
            entity.setBandKey(band.key()); entity.setLowerBound(band.lower());
            entity.setNameEn(band.nameEn()); entity.setNameAr(band.nameAr());
            entity.setPosition(position++); entity.setCreatedAt(clock.instant());
            bandRows.save(entity);
        }
        return schemes.of(schoolId, academy.getAcademyKey()).view();
    }

    // ---------------------------------------------------------------- the academy's own settings

    @Transactional
    public StructureDto.Academy update(String schoolId, String academyKey, StructureDto.UpdateAcademyRequest body) {
        var academy = require(schoolId, academyKey);
        if (body == null) throw ApiException.badRequest("nothing to change");
        if (!blank(body.nameEn())) academy.setNameEn(body.nameEn().trim());
        if (!blank(body.nameAr())) academy.setNameAr(body.nameAr().trim());
        if (!blank(body.curriculumType())) {
            if (!StructureDto.CURRICULUM_TYPES.contains(body.curriculumType()))
                throw ApiException.badRequest("curriculumType is one of " + StructureDto.CURRICULUM_TYPES);
            academy.setCurriculumType(body.curriculumType());
        }
        applyNaming(academy, body.gradeStyle(), body.gradeOffset(), body.gradeWordEn(), body.gradeWordAr());
        if (body.directorId() != null) setDirector(schoolId, academy, body.directorId().isBlank() ? null : body.directorId());
        academy.setUpdatedAt(clock.instant());
        academies.save(academy);
        return view(schoolId, academy);
    }

    /** The grade naming of one academy — the same four fields the bulk "rename grades" tool writes. */
    void applyNaming(AcademyEntity academy, String style, Integer offset, String wordEn, String wordAr) {
        if (!blank(style)) {
            if (!StructureDto.GRADE_STYLES.contains(style)) throw ApiException.badRequest("style is one of " + StructureDto.GRADE_STYLES);
            academy.setGradeStyle(style);
        }
        if (offset != null) {
            if (offset < -3 || offset > 3) throw ApiException.badRequest("the grade offset is between -3 and 3");
            academy.setGradeOffset(offset);
        }
        if (!blank(wordEn)) academy.setGradeWordEn(wordEn.trim());
        if (!blank(wordAr)) academy.setGradeWordAr(wordAr.trim());
    }

    /**
     * The academy director: a MANAGERIAL account of this school. Her department is a `staff_scopes` row with this
     * curriculum and no subject, so changing her here moves that row and the academy keeps the id beside it.
     */
    @Transactional
    public void setDirector(String schoolId, AcademyEntity academy, String userId) {
        var mine = scopes.findBySchoolIdOrderByUserIdAsc(schoolId).stream()
                .filter(s -> s.getSubject() == null && academy.getAcademyKey().equals(key(s.getCurriculum()))).toList();
        if (userId == null) { scopes.deleteAll(mine); academy.setDirectorId(null); return; }
        var user = users.findById(userId).filter(u -> schoolId.equals(u.getSchoolId()))
                .orElseThrow(() -> ApiException.notFound("user"));
        if (!"MANAGERIAL".equals(user.getRole()))
            throw ApiException.badRequest("An academy director is a department manager (MANAGERIAL), and " + user.getEmail() + " is not one.");
        scopes.deleteAll(mine.stream().filter(s -> !s.getUserId().equals(userId)).toList());
        if (mine.stream().noneMatch(s -> s.getUserId().equals(userId))) {
            var row = new StaffScopeEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId); row.setUserId(userId);
            row.setSubject(null); row.setCurriculum(academy.getAcademyKey()); row.setCreatedAt(clock.instant());
            scopes.save(row);
        }
        academy.setDirectorId(userId);
    }

    // ---------------------------------------------------------------- helpers

    static String key(String value) { return value == null ? null : value.trim().toLowerCase(Locale.ROOT); }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static LocalDate date(String value, String field) {
        try { return LocalDate.parse(value); }
        catch (RuntimeException e) { throw ApiException.badRequest(field + " is a date as yyyy-MM-dd"); }
    }
}
