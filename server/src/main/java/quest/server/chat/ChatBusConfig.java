package quest.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Which {@link ChatBus} this instance runs: PostgreSQL `LISTEN/NOTIFY` when the datasource is PostgreSQL (QA,
 * production, the Testcontainers tag), the in-process bus otherwise (H2, the suite). Decided from the live
 * connection's product name rather than from a profile, because the PostgreSQL tests run under the `h2` profile
 * with a container behind them. `quest.chat.bus=postgres|memory` forces one.
 */
@Configuration
public class ChatBusConfig {
    private static final Logger log = LoggerFactory.getLogger(ChatBusConfig.class);

    @Bean
    public ChatBus chatBus(DataSource dataSource, ObjectMapper mapper,
                           @Value("${quest.chat.bus:auto}") String mode, @Value("${quest.chat.notify-poll-millis:250}") int pollMillis) {
        boolean postgres = switch (mode) { case "postgres" -> true; case "memory" -> false; default -> isPostgres(dataSource); };
        log.info("chat bus: {}", postgres ? "postgresql LISTEN/NOTIFY" : "in-memory (single instance)");
        return postgres ? new PostgresChatBus(dataSource, mapper, pollMillis).start() : new InMemoryChatBus();
    }

    static boolean isPostgres(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) { return "PostgreSQL".equalsIgnoreCase(c.getMetaData().getDatabaseProductName()); }
        catch (Exception e) { log.warn("chat bus: could not read the datasource product ({}); using the in-memory bus", e.toString()); return false; }
    }
}
