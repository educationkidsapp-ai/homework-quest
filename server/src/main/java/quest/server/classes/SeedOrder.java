package quest.server.classes;

import org.springframework.core.Ordered;

/**
 * The order the startup seeds run in, in one place so that adding a fourth is a decision rather than an accident.
 *
 * <p>Spring runs `CommandLineRunner`s in bean-definition order unless they carry `@Order` — which is to say, in
 * whatever order component scanning happened to find them. Every seed but {@link SeedReset} used to rely on that,
 * and each of them depends on the one before it: {@link quest.server.grading.AttemptSeed} looks its classes and
 * children up <em>by name</em> and throws when one is missing, and {@link quest.server.content.ContentSeed}
 * publishes its samples into a section. A rename, a package move or a Spring upgrade could have reordered them at
 * any time, and the symptom would have been a QA environment that failed to seed with "no class is named 1A".
 *
 * <p>The numbers are spaced so a seed can be put between two of them later without renumbering the rest.
 * `SeedOrderTest` asserts the three against the container rather than against this file, so the order that is
 * pinned is the order that actually runs.
 */
public final class SeedOrder {
    private SeedOrder() {}

    /** {@link SeedReset}: the wipe, before anything writes. */
    public static final int RESET = Ordered.HIGHEST_PRECEDENCE;
    /** {@link SchoolSeed}: schools, sections, staff, assignments and the roster — what everything else names. */
    public static final int SCHOOLS = 100;
    /** {@link quest.server.content.ContentSeed}: the sample lessons, published into sections that now exist. */
    public static final int CONTENT = 200;
    /** {@link quest.server.grading.AttemptSeed}: attempts, which need both a child and a lesson to hang off. */
    public static final int ATTEMPTS = 300;
}
