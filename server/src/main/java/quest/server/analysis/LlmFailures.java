package quest.server.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Rejected model answers are written to `data/llm-failures/` so a prompt can be tuned against what the model actually wrote. */
final class LlmFailures {
    private LlmFailures() {}
    static final Path DIR = Path.of(System.getProperty("quest.failures.dir", "data/llm-failures"));

    static void keep(String prompt, String text, List<String> errors) {
        try {
            Files.createDirectories(DIR);
            Path f = DIR.resolve(prompt + "-" + Instant.now().toEpochMilli() + ".txt");
            Files.writeString(f, "ERRORS:\n- " + String.join("\n- ", errors) + "\n\nANSWER:\n" + text);
        } catch (Exception e) { LoggerFactory.getLogger(LlmFailures.class).debug("could not keep failure: {}", e.toString()); }
    }
}
