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

    /**
     * `id → name` for a set of schools, in one query. Names are not tenant data — every list that shows a school
     * column (users, all lessons, the flag audit) needs them for rows it is already allowed to see, and looking each
     * one up separately is a query per row.
     */
    public Map<String, String> namesOf(java.util.Collection<String> ids) {
        // A `LinkedHashMap` even when empty: `users.school_id` is nullable (the platform ADMIN), and an immutable
        // `Map` refuses even to be *asked* about a null key.
        var out = new java.util.LinkedHashMap<String, String>();
        if (ids == null || ids.isEmpty()) return out;
        schools.findAllById(ids).forEach(s -> out.put(s.getId(), s.getName()));
        return out;
    }

    /** The school row when the caller may see it at all: an Admin always, anyone else only their own. 404 otherwise. */
    public Entities.SchoolEntity requireVisible(Principals.User caller, String id) {
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
        school.setGradeOptionsJson(json.write(validGrades(request.gradeOptions(), List.of(1, 2, 3, 4, 5, 6))));
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

    /**
     * The Overview tab's four counts (§6 screen 5), native like {@link #summary} and for the same reason: they are
     * per school and must not be re-scoped by the request's tenant filter, which an Admin looking at another school's
     * page does not have. Four constant statements — the page is one school, not a list.
     */
    public SchoolDto.School toDto(Entities.SchoolEntity school) {
        return new SchoolDto.School(school.getId(), school.getName(), school.getCode(), curricula(school), grades(school),
                school.getThemeJson(), school.getFeatureFlagsJson(), school.getStatus(),
                school.getCreatedAt() == null ? 0 : school.getCreatedAt().toEpochMilli(),
                count("SELECT COUNT(*) FROM children WHERE school_id = :id AND deleted_at IS NULL", school.getId()),
                count("SELECT COUNT(*) FROM users WHERE school_id = :id AND role = 'TEACHER' AND status <> 'disabled'", school.getId()),
                count("SELECT COUNT(*) FROM lessons WHERE school_id = :id", school.getId()),
                count("SELECT COUNT(*) FROM classes WHERE school_id = :id", school.getId()));
    }

    /**
     * §6 screen 1: the logo and name of the school an address already belongs to, so the sign-in page can fade it in
     * once the person has typed their email.
     *
     * <p>It answers <strong>only when the domain belongs to exactly one school</strong>. That is what keeps it from
     * becoming an enumeration oracle: a shared domain (`gmail.com`, a group of schools on one MAT domain) matches
     * several tenants and answers nothing, and a domain that matches none answers nothing either — so the only thing
     * a caller can learn is the branding of a school the address they typed is already a member of. The route is
     * rate-limited like sign-in ({@link quest.server.auth.SignInRateLimiter}) so it cannot be swept either.
     *
     * <p>The domain is matched natively and case-insensitively on `users.email`: `users` is a tenant table, and this
     * runs with no scope at all (nobody is signed in), so the query names its own condition and returns ids only.
     */
    public SchoolDto.SchoolLogo logoByEmail(String email) {
        String domain = domainOf(email);
        if (domain == null) return null;
        @SuppressWarnings("unchecked")
        List<String> schoolIds = em.createNativeQuery("SELECT DISTINCT u.school_id FROM users u WHERE u.school_id IS NOT NULL AND LOWER(u.email) LIKE :suffix")
                .setParameter("suffix", "%@" + domain).setMaxResults(2).getResultList();
        if (schoolIds.size() != 1) return null;                                 // none, or a domain several schools share
        var school = schools.findById(schoolIds.get(0)).filter(s -> "active".equals(s.getStatus())).orElse(null);
        if (school == null) return null;
        return new SchoolDto.SchoolLogo(school.getName(), themes.themeOf(school).logoUrl());
    }

    /**
     * The part after the single `@`, lower-cased, or null when the value is not an address whose domain is safe to
     * put in a `LIKE`. Only letters, digits, dots and hyphens are accepted, so `%` and `_` — the wildcards that would
     * turn the lookup above into a sweep of every school — can never reach the pattern.
     */
    static String domainOf(String email) {
        if (email == null) return null;
        String trimmed = email.trim().toLowerCase(Locale.ROOT);
        int at = trimmed.indexOf('@');
        if (at <= 0 || at != trimmed.lastIndexOf('@') || at == trimmed.length() - 1) return null;
        String domain = trimmed.substring(at + 1);
        if (domain.length() > 255 || !domain.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?") || !domain.contains(".")) return null;
        return domain;
    }

    private List<String> curricula(Entities.SchoolEntity school) { return json.read(school.getCurriculumOptionsJson(), new TypeReference<List<String>>() {}); }
    private List<Integer> grades(Entities.SchoolEntity school) { return json.read(school.getGradeOptionsJson(), new TypeReference<List<Integer>>() {}); }
}
