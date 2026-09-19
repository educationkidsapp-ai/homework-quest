package quest.server.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one "now" the server reads. Anything that asks what day it is takes this bean instead of calling
 * {@code LocalDate.now()} itself, so a report can be pinned to a known day in a test rather than passing only on the
 * days of the week its fixture happens to suit — the reason `SchoolDataTest` used to fail every Friday and Saturday.
 *
 * <p>UTC, because the report windows and the per-day series are bucketed by UTC day. A school's own day is this clock
 * re-zoned; see {@code SchoolCalendar.today}.
 */
@Configuration
public class TimeConfig {
    @Bean public Clock clock() { return Clock.systemUTC(); }
}
