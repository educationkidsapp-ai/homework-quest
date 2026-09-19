package quest.server.dashboard;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.config.QuestProperties;
import quest.server.tenancy.Entities.SchoolEntity;

/**
 * The numbers behind three screens: the School page's Usage and Billing tabs (§6 screen 5), Managerial's School usage
 * (§6 screen 19) and Admin's Platform usage & cost (§6 screen 10).
 *
 * <p><strong>Batched, always.</strong> Each report is a fixed handful of grouped statements — six for a school's
 * usage, two for its billing, five for the platform — whatever the number of schools, teachers, children, lessons or
 * days in the window. Per-week and per-month buckets are built in Java from per-day rows so the SQL stays inside the
 * intersection of PostgreSQL 16 and H2 in PostgreSQL mode; `UsageQueryCountTest` pins the counts as the seed grows.
 *
 * <p><strong>Scope.</strong> Callers reach these through routes that have already proved they may see the school
 * (`SchoolService.requireVisible`, or the token's own school for `/school/**`); every statement then names that
 * `school_id` itself rather than relying on a Hibernate filter an Admin does not have. See {@link Reports}.
 *
 * <p><strong>Money.</strong> Cost is `tokens / 1000 × quest.llm.price-per-1k-tokens`, and that price is an
 * assumption — one blended rate over a combined input+output token count. Every answer carries the rate it used so
 * the screen can say so; see {@link QuestProperties.Llm}.
 */
@Service
public class UsageService {
    private final EntityManager em; private final QuestProperties props; private final Clock clock;

    public UsageService(EntityManager em, QuestProperties props, Clock clock) { this.em = em; this.props = props; this.clock = clock; }

    /** Today in UTC, from the injected clock — never {@code LocalDate.now()}; see {@code TimeConfig}. */
    private LocalDate today() { return LocalDate.now(clock.withZone(ZoneOffset.UTC)); }

    double price() { return props.llm().price(); }

    // ---------------------------------------------------------------- one school (§6 screens 5 and 19)

    @Transactional(readOnly = true)
    public SchoolDataDto.SchoolUsage schoolUsage(SchoolEntity school, String from, String to) {
        var window = Reports.window(from, to, today());
        var scope = Map.<String, Object>of("schoolId", school.getId(),
                "windowFrom", window.fromInstant(), "windowEnd", window.toExclusive());

        int children = (int) one("SELECT COUNT(*) FROM children WHERE school_id = :schoolId AND deleted_at IS NULL", scope);
        int activeFamilies = (int) one("SELECT COUNT(DISTINCT c.parent_id) FROM children c JOIN attempts a ON a.child_id = c.id"
                + " WHERE c.school_id = :schoolId AND c.deleted_at IS NULL"
                + " AND a.answered_at >= :windowFrom AND a.answered_at < :windowEnd", scope);

        // "A play" is one child working through one lesson on one day, not one answer: a child who answers twelve
        // questions has played once. Counted as distinct (child, lesson) pairs per day.
        var playsPerDay = days(Reports.rows(Reports.bind(em,
                "SELECT CAST(a.answered_at AS DATE) AS d, COUNT(DISTINCT a.child_id || ':' || a.lesson_id) AS n"
                        + " FROM attempts a JOIN children c ON c.id = a.child_id"
                        + " WHERE c.school_id = :schoolId AND a.answered_at >= :windowFrom AND a.answered_at < :windowEnd"
                        + " GROUP BY CAST(a.answered_at AS DATE) ORDER BY d", scope)), window);

        var publishedPerDay = Reports.rows(Reports.bind(em,
                "SELECT CAST(l.published_at AS DATE) AS d, COUNT(*) AS n FROM lessons l"
                        + " WHERE l.school_id = :schoolId AND l.status = 'published'"
                        + " AND l.published_at >= :windowFrom AND l.published_at < :windowEnd"
                        + " GROUP BY CAST(l.published_at AS DATE) ORDER BY d", scope));

        return new SchoolDataDto.SchoolUsage(school.getId(), school.getName(), window.from().toString(), window.to().toString(),
                children, activeFamilies, playsPerDay, weeks(publishedPerDay, window), teacherConsistency(school.getId(), window, scope));
    }

    /**
     * §6 screen 19, "teachers' publishing consistency": in how many of the window's weeks each teacher published at
     * least one lesson. Three statements — the teachers, their published days, and when each last published —
     * whatever the number of teachers.
     */
    private List<SchoolDataDto.TeacherConsistency> teacherConsistency(String schoolId, Reports.Window window, Map<String, Object> scope) {
        var teachers = new LinkedHashMap<String, String>();
        for (var row : Reports.rows(Reports.bind(em,
                "SELECT u.id, COALESCE(u.display_name, u.email) FROM users u WHERE u.school_id = :schoolId"
                        + " AND u.role = 'TEACHER' AND u.status <> 'disabled' ORDER BY u.email", scope)))
            teachers.put(Reports.text(row[0]), Reports.text(row[1]));
        if (teachers.isEmpty()) return List.of();

        var perTeacher = new LinkedHashMap<String, int[]>();                     // [lessons, weeks touched]
        var weeksSeen = new LinkedHashMap<String, java.util.Set<LocalDate>>();
        for (var row : Reports.rows(Reports.bind(em,
                "SELECT k.teacher_id, CAST(l.published_at AS DATE) AS d, COUNT(*) AS n FROM lessons l"
                        + " JOIN classes k ON k.id = l.class_id WHERE l.school_id = :schoolId AND l.status = 'published'"
                        + " AND k.teacher_id IS NOT NULL AND l.published_at >= :windowFrom AND l.published_at < :windowEnd"
                        + " GROUP BY k.teacher_id, CAST(l.published_at AS DATE)", scope))) {
            String teacherId = Reports.text(row[0]);
            perTeacher.computeIfAbsent(teacherId, k -> new int[1])[0] += (int) Reports.number(row[2]);
            weeksSeen.computeIfAbsent(teacherId, k -> new java.util.LinkedHashSet<>()).add(Reports.weekOf(Reports.day(row[1])));
        }

        var lastPublished = new LinkedHashMap<String, Long>();
        for (var row : Reports.rows(Reports.bind(em,
                "SELECT k.teacher_id, MAX(l.published_at) FROM lessons l JOIN classes k ON k.id = l.class_id"
                        + " WHERE l.school_id = :schoolId AND l.status = 'published' AND k.teacher_id IS NOT NULL"
                        + " GROUP BY k.teacher_id", scope))) {
            var at = Reports.instant(row[1]);
            if (at != null) lastPublished.put(Reports.text(row[0]), at.toEpochMilli());
        }

        int weeks = weekStarts(window).size();
        var out = new ArrayList<SchoolDataDto.TeacherConsistency>(teachers.size());
        teachers.forEach((id, name) -> out.add(new SchoolDataDto.TeacherConsistency(id, name,
                perTeacher.containsKey(id) ? perTeacher.get(id)[0] : 0, weeks,
                weeksSeen.containsKey(id) ? weeksSeen.get(id).size() : 0, lastPublished.get(id))));
        return List.copyOf(out);
    }

    /**
     * §6 screen 5, Billing: what this school's lessons cost in model tokens, by the month the lesson was created —
     * which is when the tokens were actually spent, `updated_at` having moved since for reasons that cost nothing.
     */
    @Transactional(readOnly = true)
    public SchoolDataDto.SchoolBilling billing(SchoolEntity school, Integer months) {
        int wanted = Math.clamp(months == null || months <= 0 ? Reports.DEFAULT_MONTHS : months, 1, Reports.MAX_MONTHS);
        LocalDate today = today();
        LocalDate firstMonth = today.withDayOfMonth(1).minusMonths(wanted - 1L);
        var scope = Map.<String, Object>of("schoolId", school.getId(), "windowFrom", Reports.startOf(firstMonth));

        var buckets = new LinkedHashMap<String, long[]>();                       // month -> [tokens, saved]
        for (LocalDate m = firstMonth; !m.isAfter(today.withDayOfMonth(1)); m = m.plusMonths(1))
            buckets.put(Reports.monthOf(m), new long[2]);
        for (var row : Reports.rows(Reports.bind(em,
                "SELECT CAST(l.created_at AS DATE) AS d, SUM(l.token_usage) AS t, SUM(l.tokens_saved) AS s FROM lessons l"
                        + " WHERE l.school_id = :schoolId AND l.created_at >= :windowFrom"
                        + " GROUP BY CAST(l.created_at AS DATE) ORDER BY d", scope))) {
            var bucket = buckets.get(Reports.monthOf(Reports.day(row[0])));
            if (bucket == null) continue;
            bucket[0] += Reports.number(row[1]); bucket[1] += Reports.number(row[2]);
        }

        double price = price();
        var out = new ArrayList<SchoolDataDto.MonthCost>(buckets.size());
        long total = 0;
        for (var entry : buckets.entrySet()) {
            long tokens = entry.getValue()[0];
            total += tokens;
            out.add(new SchoolDataDto.MonthCost(entry.getKey(), tokens, entry.getValue()[1], Reports.cost(tokens, price)));
        }
        return new SchoolDataDto.SchoolBilling(school.getId(), school.getName(), "USD", price, List.copyOf(out),
                total, Reports.cost(total, price));
    }

    // ---------------------------------------------------------------- the platform (§6 screen 10)

    @Transactional(readOnly = true)
    public SchoolDataDto.PlatformUsage platformUsage(String from, String to) {
        var window = Reports.window(from, to, today());
        var scope = Map.<String, Object>of("windowFrom", window.fromInstant(), "windowEnd", window.toExclusive());

        int schools = (int) one("SELECT COUNT(*) FROM schools", Map.of());
        int children = (int) one("SELECT COUNT(*) FROM children WHERE deleted_at IS NULL", Map.of());
        var playsPerDay = days(Reports.rows(Reports.bind(em,
                "SELECT CAST(a.answered_at AS DATE) AS d, COUNT(DISTINCT a.child_id || ':' || a.lesson_id) AS n FROM attempts a"
                        + " WHERE a.answered_at >= :windowFrom AND a.answered_at < :windowEnd"
                        + " GROUP BY CAST(a.answered_at AS DATE) ORDER BY d", scope)), window);

        // Every cache row is one call that reached the model and was paid for; `hits` is how often a later lesson
        // reused it. So the rate §6 wants — "cache hit rate (target > 90 %)" — is hits / (hits + calls).
        long calls = 0, hits = 0;
        for (var row : Reports.rows(Reports.bind(em,
                "SELECT COUNT(*) AS c, COALESCE(SUM(hits), 0) AS h FROM analysis_cache WHERE created_at >= :windowFrom AND created_at < :windowEnd"
                        + " UNION ALL"
                        + " SELECT COUNT(*), COALESCE(SUM(hits), 0) FROM generation_cache WHERE created_at >= :windowFrom AND created_at < :windowEnd", scope))) {
            calls += Reports.number(row[0]); hits += Reports.number(row[1]);
        }
        double hitRate = hits + calls == 0 ? 0.0 : Math.round(hits * 10000.0 / (hits + calls)) / 10000.0;

        double price = price();
        var costs = new ArrayList<SchoolDataDto.SchoolCost>();
        // Summed from the raw tokens and rounded once, not from the per-school figures which are already rounded to
        // four decimals: adding rounded numbers drifts, and the total is the one an invoice is compared against.
        long totalTokens = 0;
        for (var row : Reports.rows(Reports.bind(em,
                "SELECT s.id, s.name, COALESCE(SUM(l.token_usage), 0) AS t, COALESCE(SUM(l.tokens_saved), 0) AS v, COUNT(l.id) AS n"
                        + " FROM schools s LEFT JOIN lessons l ON l.school_id = s.id"
                        + " AND l.created_at >= :windowFrom AND l.created_at < :windowEnd"
                        + " GROUP BY s.id, s.name ORDER BY s.name", scope))) {
            long tokens = Reports.number(row[2]);
            totalTokens += tokens;
            costs.add(new SchoolDataDto.SchoolCost(Reports.text(row[0]), Reports.text(row[1]), tokens,
                    Reports.number(row[3]), Reports.cost(tokens, price), (int) Reports.number(row[4])));
        }

        return new SchoolDataDto.PlatformUsage(window.from().toString(), window.to().toString(), schools, children,
                playsPerDay, calls, hits, hitRate, price, List.copyOf(costs), Reports.cost(totalTokens, price));
    }

    // ---------------------------------------------------------------- shaping

    private long one(String sql, Map<String, Object> parameters) {
        return Reports.number(Reports.bind(em, sql, parameters).getSingleResult());
    }

    /** `[day, count]` rows into a gapless series: a day nobody played is a zero, not a missing point on the chart. */
    private static List<SchoolDataDto.DayCount> days(List<Object[]> rows, Reports.Window window) {
        var counted = new LinkedHashMap<LocalDate, Integer>();
        for (var row : rows) counted.put(Reports.day(row[0]), (int) Reports.number(row[1]));
        var out = new ArrayList<SchoolDataDto.DayCount>(window.days());
        for (LocalDate d = window.from(); !d.isAfter(window.to()); d = d.plusDays(1))
            out.add(new SchoolDataDto.DayCount(d.toString(), counted.getOrDefault(d, 0)));
        return List.copyOf(out);
    }

    /** The same, bucketed into the ISO weeks the window covers. */
    private static List<SchoolDataDto.WeekCount> weeks(List<Object[]> rows, Reports.Window window) {
        var counted = new LinkedHashMap<LocalDate, Integer>();
        for (LocalDate week : weekStarts(window)) counted.put(week, 0);
        for (var row : rows) {
            LocalDate week = Reports.weekOf(Reports.day(row[0]));
            counted.computeIfPresent(week, (k, v) -> v + (int) Reports.number(row[1]));
        }
        var out = new ArrayList<SchoolDataDto.WeekCount>(counted.size());
        counted.forEach((week, count) -> out.add(new SchoolDataDto.WeekCount(week.toString(), count)));
        return List.copyOf(out);
    }

    /** The Monday of every ISO week the window touches, in order. */
    private static List<LocalDate> weekStarts(Reports.Window window) {
        var out = new ArrayList<LocalDate>();
        for (LocalDate week = Reports.weekOf(window.from()); !week.isAfter(Reports.weekOf(window.to())); week = week.plusWeeks(1))
            out.add(week);
        return out;
    }
}
