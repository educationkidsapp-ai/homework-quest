package quest.server.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * D13: <strong>"no code path may assume 'the school' by id, name or constant."</strong> Adding a second school later
 * must need a row, some users and the `multiSchool` flag — never an edit to a service that quietly meant the first
 * one.
 *
 * <p>The check is a source sweep rather than an ArchUnit rule because what it forbids is a <em>string constant</em>,
 * and constants are inlined by `javac` long before ArchUnit sees the bytecode. It looks for the seeded school id in
 * every package that serves a tenant's data, and allows exactly two places:
 *
 * <ul>
 *   <li>{@link TenantContext#writeSchoolId()} — D6's fallback, the one line that is <em>about</em> the default
 *       school, and the `DEFAULT_SCHOOL` constant it reads;</li>
 *   <li>the seed classes, which create that school and the rows that belong to it.</li>
 * </ul>
 *
 * <p>An `@Entity` column default (`private String schoolId = "default"`) is allowed too: it is the migration's
 * `DEFAULT 'default'` restated for Hibernate, not a decision a service is making.
 */
class NoSchoolIdLiteralTest {
    /** The packages that read or write a tenant's data; a literal in any of them is the bug this catches. */
    private static final List<String> WATCHED = List.of(
            "admin", "teacher", "schools", "users", "children", "content", "flags", "platform", "dashboard", "classes",
            "coordinator");

    /** Seeded school ids, and the names a school might be spelled out by. */
    private static final Pattern LITERAL = Pattern.compile("\"(default|al-noor|green-valley)\"");

    /** The two exemptions of D13, by file. Adding a name here is the thing to argue about in review. */
    private static final Set<String> ALLOWED_FILES = Set.of("ContentSeed.java", "AdminSeed.java");

    @Test void no_service_controller_or_repository_names_a_school() throws IOException {
        var offenders = new ArrayList<String>();
        var scannedPackages = new ArrayList<String>();
        int scannedFiles = 0;
        for (String pkg : WATCHED) {
            Path root = Path.of("src/main/java/quest/server").resolve(pkg);
            if (!Files.isDirectory(root)) continue;
            scannedPackages.add(pkg);
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    scannedFiles++;
                    if (ALLOWED_FILES.contains(file.getFileName().toString())) continue;
                    var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (!LITERAL.matcher(line).find()) continue;
                        if (isEntityColumnDefault(line) || isSingleRowTableId(pkg, line)) continue;
                        offenders.add(pkg + "/" + file.getFileName() + ":" + (i + 1) + "  " + line.trim());
                    }
                }
            }
        }
        // A source sweep that found nothing passes for the wrong reason — a renamed package, or a working directory
        // that is not `server/` — so what it actually looked at is asserted before what it found.
        assertThat(scannedPackages).as("every watched package must exist; rename one and this rule stops watching it")
                .containsExactlyElementsOf(WATCHED);
        assertThat(scannedFiles).as("the sweep must have read real sources, not an empty tree").isGreaterThan(50);
        assertThat(offenders)
                .as("D13: read the school from TenantContext (`schoolId()` / `writeSchoolId()`), never from a literal")
                .isEmpty();
    }

    /** The one place the sweep must not fire: the JPA mirror of the migration's `DEFAULT 'default'`. */
    private static boolean isEntityColumnDefault(String line) {
        return line.contains("@Column") && line.contains("schoolId =");
    }

    /**
     * `platform_settings` is not a tenant table — there is one platform, its row's primary key happens to be spelled
     * the same way, and it names no school. The sweep is about school ids, so this one constant is not one.
     */
    private static boolean isSingleRowTableId(String pkg, String line) {
        return pkg.equals("platform") && line.contains("static final String ID =");
    }

    /** The allow-listed line itself has to keep existing, or the rule is quietly checking nothing. */
    @Test void the_default_school_lives_in_exactly_one_place() throws IOException {
        String source = Files.readString(Path.of("src/main/java/quest/server/tenancy/TenantContext.java"), StandardCharsets.UTF_8);
        assertThat(source).contains("public static final String DEFAULT_SCHOOL = \"default\";");
        assertThat(TenantContext.DEFAULT_SCHOOL).isEqualTo("default");
    }
}
