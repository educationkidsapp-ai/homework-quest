package quest.server.dashboard;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Map;
import quest.server.config.ApiException;

/**
 * The shared plumbing of the P3.0 report endpoints: the date window they all take, and the handful of conversions
 * their native queries need.
 *
 * <p><strong>Why native.</strong> Every figure here is "for school X" while the caller may have no scope of their own
 * — an Admin reading another school's Usage tab has none, and the platform report spans every tenant. The Hibernate
 * filter cannot express that, so each query names its own `school_id` (or joins one) and the <em>route</em> is what
 * proves the caller may see that school: `SchoolService.requireVisible` for an Admin's `{id}` routes, the token's own
 * school for `/school/**`. That is the same pattern `SchoolService.summary` has used since P1.1.
 *
 * <p><strong>Portability.</strong> The SQL must run on PostgreSQL 16 and on H2 in PostgreSQL mode (the test profile),
 * so the only date function used is `CAST(ts AS DATE)`; weeks and months are bucketed from those days in Java rather
 * than with `DATE_TRUNC`, whose week semantics differ between the two.
 */
final class Reports {
    /** How far back a usage window reaches when the caller names no `from`. */
    static final int DEFAULT_DAYS = 30;
    /** How many months of billing a caller gets by default, and the most they may ask for. */
    static final int DEFAULT_MONTHS = 6, MAX_MONTHS = 24;
    /** The longest window a usage endpoint will assemble a per-day series for. */
    static final int MAX_DAYS = 400;

    private Reports() {}

    /** An inclusive day range. `to` is the last day counted; {@link #toExclusive} turns it into an instant. */
    record Window(LocalDate from, LocalDate to) {
        Instant fromInstant() { return from.atStartOfDay(ZoneOffset.UTC).toInstant(); }
        /** The first instant *after* the window, so a query can say `>= from AND < to` and include all of `to`. */
        Instant toExclusive() { return to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(); }
        int days() { return (int) (to.toEpochDay() - from.toEpochDay()) + 1; }
    }

    /**
     * The window a `?from&to` pair asks for: both ISO days, defaulting to the last {@value #DEFAULT_DAYS} days ending
     * today. A reversed or over-long range is a 400 rather than a series nobody can draw.
     */
    static Window window(String from, String to, LocalDate today) {
        LocalDate end = parse(to, "to", today);
        LocalDate start = parse(from, "from", end.minusDays(DEFAULT_DAYS - 1L));
        if (start.isAfter(end)) throw ApiException.badRequest("from must not be after to");
        var window = new Window(start, end);
        if (window.days() > MAX_DAYS) throw ApiException.badRequest("that window is longer than " + MAX_DAYS + " days");
        return window;
    }

    private static LocalDate parse(String value, String field, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return LocalDate.parse(value.trim()); }
        catch (RuntimeException e) { throw ApiException.badRequest(field + " must be a date like 2026-09-16"); }
    }

    /** The Monday of the ISO week a day falls in — how the per-week series is bucketed. */
    static LocalDate weekOf(LocalDate day) { return day.with(WeekFields.ISO.dayOfWeek(), 1); }

    /** `yyyy-MM` for a day — how the per-month billing series is bucketed. */
    static String monthOf(LocalDate day) { return day.getYear() + "-" + (day.getMonthValue() < 10 ? "0" : "") + day.getMonthValue(); }

    /**
     * Tokens at the configured price, rounded to {@value #COST_DECIMALS} places — hundredths of a cent, not cents.
     * A school in its first week spends a few thousand tokens, which at any realistic rate is well under a penny, and
     * rounding that to $0.00 would tell the Billing tab that AI is free. The screen decides how to show it.
     */
    static double cost(long tokens, double pricePer1kTokens) { return round(tokens / 1000.0 * pricePer1kTokens); }

    static final int COST_DECIMALS = 4;
    private static final double COST_SCALE = 10_000.0;

    static double round(double amount) { return Math.round(amount * COST_SCALE) / COST_SCALE; }

    // ---- reading rows back out of a native query, where every driver has its own idea of the column types

    @SuppressWarnings("unchecked")
    static List<Object[]> rows(Query query) { return query.getResultList(); }

    static long number(Object value) { return value instanceof Number n ? n.longValue() : 0L; }

    static String text(Object value) { return value == null ? null : value.toString(); }

    /** A `CAST(ts AS DATE)` comes back as `java.sql.Date` on both drivers; anything else is parsed from its text. */
    static LocalDate day(Object value) {
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof LocalDate d) return d;
        return value == null ? null : LocalDate.parse(value.toString().substring(0, 10));
    }

    static Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp t) return t.toInstant();
        if (value instanceof Instant i) return i;
        return null;
    }

    /**
     * Binds every entry of `parameters` the statement actually names, so the scoped and unscoped variants of one
     * report can share a parameter map. The name is matched on a word boundary: `:to` must not be considered present
     * because the statement happens to say `:today`.
     */
    static Query bind(EntityManager em, String sql, Map<String, Object> parameters) {
        var query = em.createNativeQuery(sql);
        parameters.forEach((name, value) -> { if (names(sql, name)) query.setParameter(name, value); });
        return query;
    }

    private static boolean names(String sql, String parameter) {
        return java.util.regex.Pattern.compile(":" + java.util.regex.Pattern.quote(parameter) + "\\b").matcher(sql).find();
    }
}
