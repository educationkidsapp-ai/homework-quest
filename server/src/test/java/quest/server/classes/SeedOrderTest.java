package quest.server.classes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import quest.server.ApiTestSupport;

/**
 * N4.2 gap: the startup seeds run in the order {@link SeedOrder} names, and it is the container that is asked
 * rather than the source.
 *
 * <p>Every seed depends on the one before it — {@link quest.server.grading.AttemptSeed} looks its classes and
 * children up by name and throws when one is missing, {@link quest.server.content.ContentSeed} publishes into a
 * section — and until N4.3 none of them said so: Spring ran them in bean-definition order, which is to say in
 * whatever order component scanning happened to find them. A package move or a Spring upgrade could have reordered
 * them at any time, and the symptom would have been a QA environment failing to seed with "no class is named 1A".
 */
class SeedOrderTest extends ApiTestSupport {
    @Autowired List<CommandLineRunner> runners;

    @Test void the_seeds_run_wipe_then_schools_then_content_then_attempts() {
        var ordered = new java.util.ArrayList<>(runners);
        AnnotationAwareOrderComparator.sort(ordered);
        // `ClassUtils.getUserClass`: a seed whose `run` is `@Transactional` is a CGLIB proxy, and the proxy's own
        // simple name is the generated one rather than the class the order is about.
        var names = ordered.stream()
                .map(r -> org.springframework.util.ClassUtils.getUserClass(r).getSimpleName())
                .filter(SEEDS::contains).toList();

        assertThat(names).as("every seed is in the context under the test profile").containsAll(SEEDS);
        assertThat(names).containsExactly("SeedReset", "SchoolSeed", "ContentSeed", "AttemptSeed");
    }

    @Test void the_order_constants_are_the_order_they_claim_to_be() {
        assertThat(SeedOrder.RESET).isLessThan(SeedOrder.SCHOOLS);
        assertThat(SeedOrder.SCHOOLS).isLessThan(SeedOrder.CONTENT);
        assertThat(SeedOrder.CONTENT).isLessThan(SeedOrder.ATTEMPTS);
    }

    private static final List<String> SEEDS = List.of("SeedReset", "SchoolSeed", "ContentSeed", "AttemptSeed");
}
