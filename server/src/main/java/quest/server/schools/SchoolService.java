package quest.server.schools;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.EntityManager;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.platform.ThemeService;
import quest.server.tenancy.Entities;
import quest.server.tenancy.SchoolRepository;

/** Schools as the Admin sees them (§6 screens 4–5) and as a parent joins them by code (§2). */
@Service
public class SchoolService {
    private static final String CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> CURRICULA = List.of("american", "british");

    private final SchoolRepository schools; private final Json json; private final EntityManager em; private final AuditService audit;
    private final ThemeService themes;
    public SchoolService(SchoolRepository schools, Json json, EntityManager em, AuditService audit, ThemeService themes) {
        this.schools = schools; this.json = json; this.em = em; this.audit = audit; this.themes = themes;
    }

    /**
     * The Admin sees every school; a Teacher or Managerial user sees exactly their own, so no other tenant's join
     * `code` is ever in the payload. The scope is an explicit `schoolId` check against the caller's token on purpose:
     * P1.2's Hibernate filter does not apply to `findById`, so a filter alone would not close this.
     */
    public List<SchoolDto.SchoolSummary> summaries(Principals.User caller) {
        var rows = caller != null && caller.isAdmin() ? schools.findAll()
                : schools.findById(ownSchoolId(caller)).map(List::of).orElseGet(List::of);
        return rows.stream().sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName())).map(this::summary).toList();
    }

    /** A school the caller may not see is a 404, not a 403: they learn nothing about the other tenants. */
    public SchoolDto.School get(Principals.User caller, String id) { return toDto(requireVisible(caller, id)); }

    /** No scope check: for callers that have already proved they may touch this school (invites, the by-code lookup). */
    public Entities.SchoolEntity require(String id) { return schools.findById(id).orElseThrow(() -> ApiException.notFound("school")); }

    Entities.SchoolEntity requireVisible(Principals.User caller, String id) {
        if (caller == null || (!caller.isAdmin() && !ownSchoolId(caller).equals(id))) throw ApiException.notFound("school");
        return require(id);
    }

    /** The one school a non-Admin caller belongs to; `""` (which matches nothing) when the token carries none. */
    private static String ownSchoolId(Principals.User caller) {
        return caller == null || caller.schoolId() == null ? "" : caller.schoolId();
    }

    /** The theme travels with the answer so the app's confirm step can run its colour transition straight away (§3). */
    public SchoolDto.JoinSchoolInfo byCode(String code) {
        var school = schools.findByCodeIgnoreCase(code == null ? "" : code.trim()).filter(s -> "active".equals(s.getStatus()))
                .orElseThrow(() -> ApiException.notFound("school"));
        var theme = themes.themeOf(school);
        return new SchoolDto.JoinSchoolInfo(school.getName(), theme.logoUrl(), curricula(school), grades(school), theme);
    }

    @Transactional
    public SchoolDto.School create(String actorUserId, SchoolDto.CreateSchoolRequest request) {
        var school = new Entities.SchoolEntity();
        school.setId(UUID.randomUUID().toString());
        school.setName(request.name().trim());
        school.setCode(request.code() == null || request.code().isBlank() ? freshCode() : claim(request.code().trim().toUpperCase(Locale.ROOT)));
        school.setCurriculumOptionsJson(json.write(validCurricula(request.curriculumOptions(), CURRICULA)));
        school.setGradeOptionsJson(json.write(validGrades(request.gradeOptions(), List.of(1, 2, 3))));
        school.setStatus("active");
        school.setCreatedAt(Instant.now());
        schools.save(school);
        audit.record(actorUserId, "school.create", "school", school.getId(), school.getId(), Map.of("name", school.getName(), "code", school.getCode()));
        return toDto(school);
    }

    @Transactional
    public SchoolDto.School update(String actorUserId, String id, SchoolDto.UpdateSchoolRequest request) {
        var school = require(id);
        if (request.name() != null && !request.name().isBlank()) school.setName(request.name().trim());
        if (request.curriculumOptions() != null) school.setCurriculumOptionsJson(json.write(validCurricula(request.curriculumOptions(), curricula(school))));
        if (request.gradeOptions() != null) school.setGradeOptionsJson(json.write(validGrades(request.gradeOptions(), grades(school))));
        if (request.status() != null) {
            String status = request.status().trim().toLowerCase(Locale.ROOT);
            if (!List.of("active", "suspended").contains(status)) throw ApiException.badRequest("status is active or suspended");
            school.setStatus(status);
        }
        schools.save(school);
        audit.record(actorUserId, "school.update", "school", school.getId(), school.getId(), Map.of("status", school.getStatus()));
        return toDto(school);
    }

    // ---- counts for the cards. Native SQL on purpose: the numbers are per school and must not be re-scoped by the
    // request's tenant filter (an Admin looking at the list has no school of their own).

    private SchoolDto.SchoolSummary summary(Entities.SchoolEntity school) {
        return new SchoolDto.SchoolSummary(school.getId(), school.getName(), school.getCode(), school.getStatus(),
                count("SELECT COUNT(*) FROM users WHERE school_id = :id AND role = 'TEACHER' AND status <> 'disabled'", school.getId()),
                count("SELECT COUNT(*) FROM children WHERE school_id = :id", school.getId()),
                count("SELECT COUNT(*) FROM lessons WHERE school_id = :id", school.getId()));
    }

    private int count(String sql, String schoolId) {
        var value = em.createNativeQuery(sql).setParameter("id", schoolId).getSingleResult();
        return value instanceof Number n ? n.intValue() : 0;
    }

    private String freshCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            var candidate = new StringBuilder(6);
            for (int i = 0; i < 6; i++) candidate.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            if (schools.findByCodeIgnoreCase(candidate.toString()).isEmpty()) return candidate.toString();
        }
        throw ApiException.badRequest("Could not find a free school code; pick one yourself.");
    }

    private String claim(String code) {
        if (schools.findByCodeIgnoreCase(code).isPresent()) throw ApiException.badRequest("That school code is taken.");
        return code;
    }

    private List<String> validCurricula(List<String> requested, List<String> fallback) {
        if (requested == null || requested.isEmpty()) return fallback;
        var cleaned = requested.stream().filter(java.util.Objects::nonNull).map(c -> c.trim().toLowerCase(Locale.ROOT)).distinct().toList();
        cleaned.stream().filter(c -> !CURRICULA.contains(c)).findFirst()
                .ifPresent(c -> { throw ApiException.badRequest("unknown curriculum " + c); });
        return cleaned;
    }

    private List<Integer> validGrades(List<Integer> requested, List<Integer> fallback) {
        if (requested == null || requested.isEmpty()) return fallback;
        var cleaned = requested.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
        cleaned.stream().filter(g -> g < 1 || g > 12).findFirst()
                .ifPresent(g -> { throw ApiException.badRequest("grade " + g + " is outside 1–12") ; });
        return cleaned;
    }

    SchoolDto.School toDto(Entities.SchoolEntity school) {
        return new SchoolDto.School(school.getId(), school.getName(), school.getCode(), curricula(school), grades(school),
                school.getThemeJson(), school.getFeatureFlagsJson(), school.getStatus(),
                school.getCreatedAt() == null ? 0 : school.getCreatedAt().toEpochMilli());
    }

    private List<String> curricula(Entities.SchoolEntity school) { return json.read(school.getCurriculumOptionsJson(), new TypeReference<List<String>>() {}); }
    private List<Integer> grades(Entities.SchoolEntity school) { return json.read(school.getGradeOptionsJson(), new TypeReference<List<Integer>>() {}); }
}
