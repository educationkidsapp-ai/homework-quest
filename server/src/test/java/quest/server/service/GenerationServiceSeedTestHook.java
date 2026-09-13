package quest.server.service;

import java.util.Set;

/** Exposes the package-private seed function to tests. */
public final class GenerationServiceSeedTestHook {
    private GenerationServiceSeedTestHook() {}
    public static String seed(Set<String> excluded, int length) { return GenerationService.seed(excluded, length); }
}
