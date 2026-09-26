package quest.server.broadcasts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import quest.server.PostgresContainerSupport;

/**
 * RM1's reviewer asked for it and RM2 owes it: the broadcast feeds are read with `IN (…)`, a `DATE` comparison and an
 * `ORDER BY … DESC` over a nullable column, and the rest of the suite only ever runs them on H2 in PostgreSQL mode.
 * H2's compatibility mode is a good approximation and not the database QA runs on, so every statement of the two
 * repositories is executed here against a real PostgreSQL 16 as well.
 *
 * <p>It asserts that the statements <em>run and shape an answer</em>, not what the numbers are — `BroadcastApiTest`
 * owns the behaviour. Skipped without Docker (this Mac), so CI is where it earns its keep. The fixtures are additive
 * and every assertion is a "contains", because {@link PostgresContainerSupport}'s container and Spring context are
 * shared with the other `postgres`-tagged tests.
 */
class PostgresBroadcastTest extends PostgresContainerSupport {
    private static final String SCHOOL = "pg-bc-school", AUTHOR = "pg-bc-nour", READER = "pg-bc-maya";

    @Autowired BroadcastRepository rows;
    @Autowired BroadcastReadRepository reads;

    @Test void every_broadcast_statement_runs_on_postgres() {
        var week = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY));
        var plan = row(BroadcastService.WEEKLY_PLAN, week, "british", null);
        var event = row(BroadcastService.EVENT, null, "british", "pg-bc-1a,pg-bc-1b");
        var expired = row(BroadcastService.ANNOUNCEMENT, null, "british", null);
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        rows.saveAll(List.of(plan, event, expired));

        var live = rows.live(SCHOOL, Instant.now(), PageRequest.of(0, 50));
        assertThat(live).extracting(Entities.BroadcastEntity::getId).contains(plan.getId(), event.getId()).doesNotContain(expired.getId());
        assertThat(rows.byAuthor(SCHOOL, AUTHOR, PageRequest.of(0, 50))).hasSizeGreaterThanOrEqualTo(3);
        assertThat(rows.weeklyPlans(SCHOOL, week, "british")).extracting(Entities.BroadcastEntity::getId).contains(plan.getId());
        assertThat(rows.weeklyPlans(SCHOOL, week, null)).as("a null curriculum matches every department").isNotEmpty();
        assertThat(rows.findOneById(plan.getId())).isPresent();

        // The grouped read the feed makes: one statement for a whole page of rows, `readerId` plus `IN (…)`.
        var mark = new Entities.BroadcastReadEntity();
        mark.setId(UUID.randomUUID().toString()); mark.setSchoolId(SCHOOL);
        mark.setBroadcastId(plan.getId()); mark.setReaderId(READER); mark.setReadAt(Instant.now());
        reads.save(mark);
        assertThat(reads.readBy(READER, List.of(plan.getId(), event.getId()))).containsExactly(plan.getId());
        assertThat(reads.findOne(plan.getId(), READER)).isPresent();
        assertThat(reads.deleteByBroadcast(plan.getId())).isOne();
        assertThat(reads.readBy(READER, List.of(plan.getId()))).isEmpty();
    }

    private static Entities.BroadcastEntity row(String kind, LocalDate week, String curriculum, String sectionIds) {
        var b = new Entities.BroadcastEntity();
        b.setId(UUID.randomUUID().toString()); b.setSchoolId(SCHOOL); b.setAuthorUserId(AUTHOR); b.setAuthorRole("MANAGERIAL");
        b.setKind(kind); b.setWeekStart(week); b.setTitle("Plan"); b.setBodyEn("Body"); b.setAudienceRoles("parents,teachers");
        b.setCurriculum(curriculum); b.setSectionIds(sectionIds); b.setCreatedAt(Instant.now());
        return b;
    }
}
