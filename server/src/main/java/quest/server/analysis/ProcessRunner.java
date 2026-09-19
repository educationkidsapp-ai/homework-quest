package quest.server.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * One external binary, run once. {@link ConversionService} shells out to `anydoc` and `tesseract` through this so it
 * can be unit-tested without Node or Tesseract installed, and so "the binary is not there" stays distinguishable
 * from "the binary said no": a missing tool is an {@link IOException}, a refused document is a non-zero exit code.
 */
public interface ProcessRunner {
    record Ran(int exitCode, String stdout, String stderr) {
        /** The first line of stderr — what an operator wants in a log line or an error message. */
        public String firstErrorLine() { return stderr == null ? "" : stderr.lines().filter(l -> !l.isBlank()).findFirst().orElse("").strip(); }
    }

    /** The binary outlived its time box. A subclass of {@link IOException} so "too slow" never reads as "not installed". */
    class Timeout extends IOException { public Timeout(String m) { super(m); } }

    /** @throws Timeout when the binary outlived {@code timeout}; @throws IOException when it could not be started at all (not on PATH). */
    Ran run(List<String> command, Path workDir, Duration timeout) throws IOException;

    /** The real thing: `ProcessBuilder`, output captured to temporary files so a chatty converter cannot fill a pipe. */
    @Component
    class Default implements ProcessRunner {
        @Override public Ran run(List<String> command, Path workDir, Duration timeout) throws IOException {
            Path out = Files.createTempFile(workDir, "stdout", ".txt"), err = Files.createTempFile(workDir, "stderr", ".txt");
            Process p = new ProcessBuilder(command).directory(workDir.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            try {
                if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) { p.destroyForcibly(); throw new Timeout(command.get(0) + " timed out after " + timeout.toSeconds() + "s"); }
            } catch (InterruptedException e) { p.destroyForcibly(); Thread.currentThread().interrupt(); throw new Timeout(command.get(0) + " was interrupted"); }
            return new Ran(p.exitValue(), Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
        }
    }
}
