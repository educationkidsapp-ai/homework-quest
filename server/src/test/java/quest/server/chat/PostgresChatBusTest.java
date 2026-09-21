package quest.server.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import quest.server.PostgresContainerSupport;

/**
 * Two Cloud Run instances, one database: what one instance publishes, the other hears through `LISTEN/NOTIFY`. The
 * context's own bus stands in for instance A (the datasource is PostgreSQL, so `ChatBusConfig` picked the
 * PostgreSQL bus) and a second bus on the same datasource is instance B. Skipped without Docker, like every test
 * behind {@link PostgresContainerSupport}.
 */
class PostgresChatBusTest extends PostgresContainerSupport {
    @Autowired ChatBus instanceA;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;

    @Test void an_event_published_on_one_instance_is_heard_on_the_other_within_the_poll_interval() throws Exception {
        assertThat(instanceA).as("a PostgreSQL datasource gets the LISTEN/NOTIFY bus").isInstanceOf(PostgresChatBus.class);
        BlockingQueue<ChatEvent> heardByA = new LinkedBlockingQueue<>();
        BlockingQueue<ChatEvent> heardByB = new LinkedBlockingQueue<>();
        instanceA.subscribe(heardByA::add);
        try (var instanceB = new PostgresChatBus(dataSource, mapper, 250).start()) {
            instanceB.subscribe(heardByB::add);
            Thread.sleep(500);                                                  // let B's LISTEN land before A publishes

            var event = ChatEvent.message("s1", "t1", "c1", "te1", "p1", "parent:p1", "c-1", "m1", "{\"id\":\"m1\"}");
            instanceA.publish(event);
            var onB = heardByB.poll(5, TimeUnit.SECONDS);
            assertThat(onB).as("B hears what A published").isEqualTo(event);
            assertThat(heardByA.poll(5, TimeUnit.SECONDS)).as("A hears its own publish through the same path").isEqualTo(event);

            var reply = ChatEvent.read("s1", "t1", "c1", "te1", "p1", "teacher:te1", "teacher", 1_700_000_000_000L);
            instanceB.publish(reply);
            assertThat(heardByA.poll(5, TimeUnit.SECONDS)).isEqualTo(reply);

            // a message too big for a NOTIFY payload crosses without its body; the hub loads it by id
            var big = ChatEvent.message("s1", "t1", "c1", "te1", "p1", "parent:p1", null, "m2", "{\"body\":\"" + "ل".repeat(3_000) + "\"}");
            instanceB.publish(big);
            var trimmed = heardByA.poll(5, TimeUnit.SECONDS);
            assertThat(trimmed).isNotNull();
            assertThat(trimmed.messageId()).isEqualTo("m2");
            assertThat(trimmed.messageJson()).isNull();
        }
    }
}
