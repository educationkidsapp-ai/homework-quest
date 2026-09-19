package quest.server;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The suite's `Clock`: the wall clock until a test pins it, and from then on a day that test chose. It replaces the
 * production bean from {@code quest.server.config.TimeConfig} in every context built from {@link ApiTestSupport}.
 *
 * <p><strong>Why.</strong> A report whose window ends "today" and a fixture that publishes "now" only agree on the
 * days of the week the fixture happens to suit; `SchoolDataTest` failed every Friday and Saturday evening on the
 * developers' machines and passed in CI. Pinning both sides to one explicit day makes the assertions hold on all
 * seven — and lets the same assertions be run twice, on a Tuesday and on a Saturday.
 *
 * <p>{@link #pinTo} fixes <em>noon</em> UTC, never midnight: the H2 driver writes an `Instant` through the JVM's own
 * zone, so a fixture seeded at the edge of a day is read back on the next one and drops out of the window. Noon has
 * hours of room on both sides.
 *
 * <p>A clock derived with {@link #withZone} shares this one's pin, because the code under test re-zones the bean
 * ({@code clock.withZone(school.zone())}) before reading it.
 */
public final class TestClock extends Clock {
    private final AtomicReference<Instant> pinned; private final ZoneId zone;

    public TestClock() { this(new AtomicReference<>(), ZoneOffset.UTC); }

    private TestClock(AtomicReference<Instant> pinned, ZoneId zone) { this.pinned = pinned; this.zone = zone; }

    /** Pins every "now" in the context to noon UTC on `day`, and answers `day` so a fixture can read as one line. */
    public LocalDate pinTo(LocalDate day) { pinned.set(day.atTime(12, 0).toInstant(ZoneOffset.UTC)); return day; }

    /** Back to the wall clock. Every test that pins releases it again, in an `@AfterEach`. */
    public void release() { pinned.set(null); }

    @Override public ZoneId getZone() { return zone; }

    @Override public Clock withZone(ZoneId other) { return new TestClock(pinned, other); }

    @Override public Instant instant() { var at = pinned.get(); return at == null ? Instant.now() : at; }
}
