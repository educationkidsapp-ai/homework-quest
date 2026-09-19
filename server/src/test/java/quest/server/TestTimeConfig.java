package quest.server;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Puts {@link TestClock} in front of the production `Clock`. Imported by {@link ApiTestSupport} rather than by the
 * handful of tests that pin, so every test shares one application context and the suite keeps its context cache.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestTimeConfig {
    @Bean @Primary public TestClock testClock() { return new TestClock(); }
}
