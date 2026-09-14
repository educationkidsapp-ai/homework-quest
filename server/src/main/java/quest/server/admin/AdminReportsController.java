package quest.server.admin;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import org.springframework.http.MediaType;
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
import quest.server.children.LessonCompletionRepository;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;
import quest.server.content.StopRepository;

/** `/admin/cache`, `/admin/usage`, `/admin/calendar`. */
@RestController
public class AdminReportsController {
    private final AnalysisCacheRepository analysisCache; private final GenerationCacheRepository generationCache; private final LessonRepository lessons; private final ChildRepository children;
    private final AttemptRepository attempts; private final LessonCompletionRepository completions; private final StopRepository stops; private final Json json;

    public AdminReportsController(AnalysisCacheRepository analysisCache, GenerationCacheRepository generationCache, LessonRepository lessons, ChildRepository children, AttemptRepository attempts, LessonCompletionRepository completions, StopRepository stops, Json json) {
        this.analysisCache = analysisCache; this.generationCache = generationCache; this.lessons = lessons; this.children = children; this.attempts = attempts; this.completions = completions; this.stops = stops; this.json = json;
    }

    @GetMapping(value = "/admin/cache", produces = MediaType.APPLICATION_JSON_VALUE)
    public String cache() {
        Map<String, List<String>> lessonsByHash = new HashMap<>();
        for (var l : lessons.findAllByOrderByDateDescCreatedAtDesc()) if (l.getSourceHash() != null) lessonsByHash.computeIfAbsent(l.getSourceHash(), k -> new ArrayList<>()).add(l.getId());
        List<CacheEntry> out = new ArrayList<>();
        for (var a : analysisCache.findAllByOrderByCreatedAtDesc()) {
            long tokens = a.getTokenUsage(); int hits = a.getHits();
            for (var g : generationCache.findBySourceHash(a.getSourceHash())) { tokens += g.getTokenUsage(); }
            out.add(new CacheEntry(a.getSourceHash(), Curriculum.valueOf(a.getCurriculum().toUpperCase()), a.getGrade(), quest.api.dto.Subject.valueOf(a.getSubject().toUpperCase()), a.getPromptVersion(), tokens, a.getCreatedAt().toEpochMilli(), hits, lessonsByHash.getOrDefault(a.getSourceHash(), List.of())));
        }
        return json.encodeShared(out, BuiltinSerializersKt.ListSerializer(CacheEntry.Companion.serializer()));
    }

    @GetMapping(value = "/admin/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    public String usage() {
        List<CourseUsage> courses = new ArrayList<>();
        for (var c : Course.Companion.getAll()) courses.add(new CourseUsage(c, (int) children.countByCurriculumAndGradeAndDeletedAtIsNull(c.getCurriculum().name().toLowerCase(), c.getGrade())));
        List<LessonUsage> lessonUsage = new ArrayList<>(); List<StopAccuracy> stopAccuracy = new ArrayList<>();
        for (LessonEntity l : lessons.findAllByOrderByDateDescCreatedAtDesc()) {
            if (!"published".equals(l.getStatus())) continue;
            var atts = attempts.findByLessonId(l.getId());
            int played = (int) atts.stream().map(AttemptEntity::getChildId).distinct().count();
            int completed = (int) completions.findByLessonId(l.getId()).stream().map(c -> c.getChildId()).distinct().count();
            lessonUsage.add(new LessonUsage(l.getId(), l.getTitle() == null ? "Lesson" : l.getTitle(), Course.Companion.parse(l.getCourseId()), AdminLessonService.kdate(l.getDate()), played, completed));
            Map<String, int[]> perStop = new LinkedHashMap<>();
            for (var a : atts) { var arr = perStop.computeIfAbsent(a.getStopId(), k -> new int[2]); if (a.getAttemptNumber() == 1) { arr[0]++; if (a.isCorrect()) arr[1]++; } }
            for (var s : stops.findByLessonId(l.getId())) { var arr = perStop.get(s.getId()); if (arr != null && arr[0] > 0) stopAccuracy.add(new StopAccuracy(l.getId(), s.getId(), s.getTitle(), s.getType(), arr[0], arr[1])); }
        }
        stopAccuracy.sort((a, b) -> Double.compare((double) a.getFirstTryCorrect() / a.getAttempts(), (double) b.getFirstTryCorrect() / b.getAttempts()));
        return json.encodeShared(new UsageResponse(courses, lessonUsage, stopAccuracy), UsageResponse.Companion.serializer());
    }

    @GetMapping(value = "/admin/calendar", produces = MediaType.APPLICATION_JSON_VALUE)
    public String calendar(@RequestParam String curriculum, @RequestParam int grade, @RequestParam int year, @RequestParam int month) {
        Course course;
        try { course = new Course(Curriculum.valueOf(curriculum.toUpperCase()), grade); } catch (IllegalArgumentException e) { throw ApiException.badRequest("bad curriculum"); }
        if (month < 1 || month > 12) throw ApiException.badRequest("bad month");
        var ym = YearMonth.of(year, month);
        var published = lessons.findByCourseIdAndStatusAndDateBetweenOrderByDateAsc(course.getKey(), "published", ym.atDay(1), ym.atEndOfMonth());
        List<CalendarDayInfo> days = new ArrayList<>();
        for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            final LocalDate day = d;
            boolean math = published.stream().anyMatch(l -> l.getDate().equals(day) && l.getSubject().equals("math"));
            boolean english = published.stream().anyMatch(l -> l.getDate().equals(day) && l.getSubject().equals("english"));
            if (math || english) days.add(new CalendarDayInfo(AdminLessonService.kdate(day), math, english));
        }
        return json.encodeShared(new CalendarResponse(course, year, month, days), CalendarResponse.Companion.serializer());
    }
}
