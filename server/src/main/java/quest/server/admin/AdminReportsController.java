package quest.server.admin;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.api.CacheEntry;
import quest.api.CalendarDayInfo;
import quest.api.CalendarResponse;
import quest.api.CourseUsage;
import quest.api.LessonUsage;
import quest.api.StopAccuracy;
import quest.api.UsageResponse;
import quest.api.dto.Course;
import quest.api.dto.Curriculum;
import quest.server.analysis.AnalysisCacheRepository;
import quest.server.analysis.GenerationCacheRepository;
import quest.server.children.AttemptRepository;
import quest.server.children.ChildRepository;
import quest.server.children.Entities.AttemptEntity;
import quest.server.children.Entities.LessonCompletionEntity;
import quest.server.children.LessonCompletionRepository;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.StopEntity;
import quest.server.content.LessonRepository;
import quest.server.content.StopRepository;
import quest.server.tenancy.TenantContext;

/**
 * `/admin/cache`, `/admin/usage`, `/admin/calendar`.
 *
 * <p><strong>Scope.</strong> `lessons` and `children` are tenant tables, so the reads below are already filtered to
 * the caller's school (`X-School-Id` for an ADMIN, the token's school otherwise) — an ADMIN who picked no school reads
 * across schools (D6). Attempts, completions and stops carry no `school_id` of their own: they are reached only by the
 * ids of the lessons that survived that filter, which is what keeps them scoped. The AI cache is global by design
 * (§2) and is scoped in {@link #cache()} instead of by a column.
 *
 * <p><strong>One query per table.</strong> Every report used to loop a query per lesson (and per course); all three
 * now read each table once and group the rows in Java, so the statement count no longer grows with the number of
 * lessons. `ReportsQueryCountTest` pins that with Hibernate statistics.
 */
@RestController
@Tag(name = "Admin reports", description = "Cache, usage and calendar")
public class AdminReportsController {
    private final AnalysisCacheRepository analysisCache; private final GenerationCacheRepository generationCache; private final LessonRepository lessons; private final ChildRepository children;
    private final AttemptRepository attempts; private final LessonCompletionRepository completions; private final StopRepository stops; private final TenantContext tenant; private final Json json;

    public AdminReportsController(AnalysisCacheRepository analysisCache, GenerationCacheRepository generationCache, LessonRepository lessons, ChildRepository children, AttemptRepository attempts, LessonCompletionRepository completions, StopRepository stops, TenantContext tenant, Json json) {
        this.analysisCache = analysisCache; this.generationCache = generationCache; this.lessons = lessons; this.children = children; this.attempts = attempts; this.completions = completions; this.stops = stops; this.tenant = tenant; this.json = json;
    }

    /**
     * The AI cache listing, scoped by the caller (§4 + §2).
     *
     * <p>`analysis_cache` and `generation_cache` have no `school_id` and are not going to get one: the whole point is
     * that two schools uploading the same worksheet pay for the analysis once. The <em>listing</em> is scoped instead:
     * a caller with a school sees only the entries at least one of her own lessons is built on, and `lessonIds` holds
     * her lessons alone — an ADMIN who picked no school keeps the global view.
     *
     * <p>`hits` is a single global counter on the row, so it cannot be attributed to a school after the fact; in a
     * scoped listing it is reported as the number of the caller's own lessons that share the entry, capped by the real
     * counter so it can never overstate the reuse that actually happened. `tokenUsage` stays the platform's cost of
     * producing the entry — it was paid once, for everyone.
     */
    @PreAuthorize("@permit.has('cache.read')")
    @GetMapping(value = "/admin/cache", produces = MediaType.APPLICATION_JSON_VALUE)
    public String cache() {
        boolean scoped = tenant.schoolId() != null;
        Map<String, List<String>> lessonsByHash = new HashMap<>();
        for (var l : lessons.findAllByOrderByDateDescCreatedAtDesc()) if (l.getSourceHash() != null) lessonsByHash.computeIfAbsent(l.getSourceHash(), k -> new ArrayList<>()).add(l.getId());

        var entries = analysisCache.findAllByOrderByCreatedAtDesc().stream().filter(a -> !scoped || lessonsByHash.containsKey(a.getSourceHash())).toList();
        Set<String> hashes = entries.stream().map(a -> a.getSourceHash()).collect(Collectors.toSet());
        Map<String, Long> generationTokens = new HashMap<>();
        if (!hashes.isEmpty()) for (Object[] row : generationCache.sumTokensBySourceHash(hashes)) generationTokens.put((String) row[0], ((Number) row[1]).longValue());

        List<CacheEntry> out = new ArrayList<>();
        for (var a : entries) {
            var lessonIds = lessonsByHash.getOrDefault(a.getSourceHash(), List.of());
            long tokens = a.getTokenUsage() + generationTokens.getOrDefault(a.getSourceHash(), 0L);
            int hits = scoped ? Math.min(a.getHits(), lessonIds.size()) : a.getHits();
            out.add(new CacheEntry(a.getSourceHash(), Curriculum.valueOf(a.getCurriculum().toUpperCase()), a.getGrade(), quest.api.dto.Subject.valueOf(a.getSubject().toUpperCase()), a.getPromptVersion(), tokens, a.getCreatedAt().toEpochMilli(), hits, lessonIds));
        }
        return json.encodeShared(out, BuiltinSerializersKt.ListSerializer(CacheEntry.Companion.serializer()));
    }

    @PreAuthorize("@permit.has('usage.read')")
    @GetMapping(value = "/admin/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    public String usage() {
        Map<String, Long> childrenByCourse = new HashMap<>();
        for (Object[] row : children.countByCourse()) childrenByCourse.put(row[0] + "/" + row[1], ((Number) row[2]).longValue());
        List<CourseUsage> courses = new ArrayList<>();
        for (var c : Course.Companion.getAll()) courses.add(new CourseUsage(c, childrenByCourse.getOrDefault(c.getKey(), 0L).intValue()));

        var published = lessons.findAllByOrderByDateDescCreatedAtDesc().stream().filter(l -> "published".equals(l.getStatus())).toList();
        var lessonIds = published.stream().map(LessonEntity::getId).toList();
        Map<String, List<AttemptEntity>> attemptsByLesson = lessonIds.isEmpty() ? Map.of() : attempts.findByLessonIdIn(lessonIds).stream().collect(Collectors.groupingBy(AttemptEntity::getLessonId));
        Map<String, List<LessonCompletionEntity>> completionsByLesson = lessonIds.isEmpty() ? Map.of() : completions.findByLessonIdIn(lessonIds).stream().collect(Collectors.groupingBy(LessonCompletionEntity::getLessonId));
        Map<String, List<StopEntity>> stopsByLesson = lessonIds.isEmpty() ? Map.of() : stops.findByLessonIdIn(lessonIds).stream().collect(Collectors.groupingBy(StopEntity::getLessonId));

        List<LessonUsage> lessonUsage = new ArrayList<>(); List<StopAccuracy> stopAccuracy = new ArrayList<>();
        for (LessonEntity l : published) {
            var atts = attemptsByLesson.getOrDefault(l.getId(), List.of());
            int played = (int) atts.stream().map(AttemptEntity::getChildId).distinct().count();
            int completed = (int) completionsByLesson.getOrDefault(l.getId(), List.of()).stream().map(LessonCompletionEntity::getChildId).distinct().count();
            lessonUsage.add(new LessonUsage(l.getId(), l.getTitle() == null ? "Lesson" : l.getTitle(), Course.Companion.parse(l.getCourseId()), AdminLessonService.kdate(l.getDate()), played, completed));
            Map<String, int[]> perStop = new LinkedHashMap<>();
            for (var a : atts) { var arr = perStop.computeIfAbsent(a.getStopId(), k -> new int[2]); if (a.getAttemptNumber() == 1) { arr[0]++; if (a.isCorrect()) arr[1]++; } }
            for (var s : stopsByLesson.getOrDefault(l.getId(), List.of())) { var arr = perStop.get(s.getId()); if (arr != null && arr[0] > 0) stopAccuracy.add(new StopAccuracy(l.getId(), s.getId(), s.getTitle(), s.getType(), arr[0], arr[1])); }
        }
        stopAccuracy.sort((a, b) -> Double.compare((double) a.getFirstTryCorrect() / a.getAttempts(), (double) b.getFirstTryCorrect() / b.getAttempts()));
        return json.encodeShared(new UsageResponse(courses, lessonUsage, stopAccuracy), UsageResponse.Companion.serializer());
    }

    @PreAuthorize("@permit.has('calendar.read')")
    @GetMapping(value = "/admin/calendar", produces = MediaType.APPLICATION_JSON_VALUE)
    public String calendar(@RequestParam String curriculum, @RequestParam int grade, @RequestParam int year, @RequestParam int month) {
        Course course;
        try { course = new Course(Curriculum.valueOf(curriculum.toUpperCase()), grade); } catch (IllegalArgumentException e) { throw ApiException.badRequest("bad curriculum"); }
        if (month < 1 || month > 12) throw ApiException.badRequest("bad month");
        var ym = YearMonth.of(year, month);
        Map<LocalDate, List<LessonEntity>> byDay = lessons.findByCourseIdAndStatusAndDateBetweenOrderByDateAsc(course.getKey(), "published", ym.atDay(1), ym.atEndOfMonth())
                .stream().collect(Collectors.groupingBy(LessonEntity::getDate));
        List<CalendarDayInfo> days = new ArrayList<>();
        for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            var onThatDay = byDay.get(d);
            if (onThatDay == null) continue;
            boolean math = onThatDay.stream().anyMatch(l -> l.getSubject().equals("math"));
            boolean english = onThatDay.stream().anyMatch(l -> l.getSubject().equals("english"));
            if (math || english) days.add(new CalendarDayInfo(AdminLessonService.kdate(d), math, english));
        }
        return json.encodeShared(new CalendarResponse(course, year, month, days), CalendarResponse.Companion.serializer());
    }
}
