package quest.server.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import quest.server.config.ApiException;

/**
 * The day window every usage report filters on. `to` is the last day <em>counted</em>, so the exclusive bound a query
 * compares against must be the first instant of the day after it — a bound at the start of `to` would silently drop
 * everything published on the last day of the window, which by default is today.
 */
class ReportsTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 19);   // a Saturday

    @Test void the_exclusive_bound_is_the_start_of_the_day_after_to() {
        var window = Reports.window("2026-09-13", "2026-09-19", TODAY);
        assertThat(window.from()).isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(window.to()).isEqualTo(TODAY);
        assertThat(window.fromInstant()).isEqualTo(Instant.parse("2026-09-13T00:00:00Z"));
        assertThat(window.toExclusive()).isEqualTo(Instant.parse("2026-09-20T00:00:00Z"));
        assertThat(window.days()).isEqualTo(7);
        assertThat(Instant.parse("2026-09-19T23:59:59Z")).as("the last second of `to` is inside").isBefore(window.toExclusive());
    }

    @Test void the_default_window_is_thirty_days_ending_today_inclusive() {
        var window = Reports.window(null, "", TODAY);
        assertThat(window.to()).isEqualTo(TODAY);
        assertThat(window.from()).isEqualTo(TODAY.minusDays(29));
        assertThat(window.days()).isEqualTo(Reports.DEFAULT_DAYS);
        assertThat(window.toExclusive()).isEqualTo(TODAY.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
    }

    @Test void a_single_day_window_covers_exactly_that_day() {
        var window = Reports.window("2026-09-19", "2026-09-19", TODAY);
        assertThat(window.days()).isEqualTo(1);
        assertThat(java.time.Duration.between(window.fromInstant(), window.toExclusive())).isEqualTo(java.time.Duration.ofDays(1));
    }

    @Test void a_reversed_over_long_or_unparsable_window_is_a_400() {
        assertThatThrownBy(() -> Reports.window("2026-09-19", "2026-09-18", TODAY)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Reports.window(TODAY.minusDays(Reports.MAX_DAYS).toString(), TODAY.toString(), TODAY))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Reports.window("yesterday", null, TODAY)).isInstanceOf(ApiException.class);
        assertThat(Reports.window(TODAY.minusDays(Reports.MAX_DAYS - 1L).toString(), null, TODAY).days()).isEqualTo(Reports.MAX_DAYS);
    }
}
