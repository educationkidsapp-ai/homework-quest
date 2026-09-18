package quest.server.classes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.config.QuestProperties;
import quest.server.tenancy.TenantContext;

/**
 * One school big enough to judge the dashboard by: 30 sections, 40 teachers, 60 teaching assignments and 600 children,
 * read from `resources/seed/*.csv` (`docs/plan.md` N1.1, `docs/prompts/dashboard-first-one-school.md` §9 Phase 1).
 *
 * <p><strong>It goes in through the services, not through SQL.</strong> {@link SectionService},
 * {@link TeachingStaffService} and {@link RosterService} are what the Admin dashboard calls, so the seeded school gets
 * the same validation, the same join codes, the same one-time passwords and the same "one teacher per subject per
 * class" refusal as a school an Admin types in by hand — a seed that wrote rows directly could hold data the API
 * itself would reject.
 *
 * <p><strong>Off unless asked.</strong> `quest.seed.school` (SEED_SCHOOL) gates it, `prod` is not in the profile list,
 * and a re-run adds nothing: a section is matched by curriculum + grade + name, a teacher by email and a child by her
 * name within her class, so the second run logs the same counts and writes no row.
 *
 * <p><strong>Passwords.</strong> With SEED_STAFF_PASSWORD set, every seeded teacher gets that one password with
 * `must_change_password` cleared, which is what lets an e2e run sign in as any of them; the value is never logged.
 * Without it each teacher keeps the one-time password {@link TemporaryPasswords} generated — nothing here can print
 * it, and an Admin hands one out with `POST /admin/teachers/{id}/password`.
 */
@Component
@Profile({"qa", "h2", "test"})
public class SchoolSeed implements CommandLineRunner {
    /** The environment variable that makes the seeded teachers signable-in; never its value. */
    static final String STAFF_PASSWORD_ENV = "SEED_STAFF_PASSWORD";
    private static final String ACTOR = "seed";
    private static final Logger log = LoggerFactory.getLogger(SchoolSeed.class);

    private final SectionService sections; private final TeachingStaffService staff; private final RosterService rosters;
    private final UserRepository users; private final PasswordEncoder encoder; private final TenantContext tenant;
    private final QuestProperties props;

    public SchoolSeed(SectionService sections, TeachingStaffService staff, RosterService rosters, UserRepository users,
                      PasswordEncoder encoder, TenantContext tenant, QuestProperties props) {
        this.sections = sections; this.staff = staff; this.rosters = rosters; this.users = users;
        this.encoder = encoder; this.tenant = tenant; this.props = props;
    }

    /** What one load wrote; a re-run answers zeroes. */
    public record Counts(int classes, int teachers, int assignments, int children) {}

    /** The sections the school has after `classes.csv`, by lower-cased name, and how many of them are new. */
    private record Sections(Map<String, String> byName, int created) {}

    @Override public void run(String... args) {
        var seed = props.seed();
        if (seed == null || !seed.school()) return;
        load(TenantContext.DEFAULT_SCHOOL);
    }

    /**
     * Loads the four files into one school. The scope is set the way an Admin who picked that school with
     * `X-School-Id` sets it, so every query underneath is filtered by it exactly as it is during a request.
     */
    public Counts load(String schoolId) {
        tenant.set("ADMIN", null, schoolId);
        try {
            var caller = new Principals.User(ACTOR, ACTOR + "@" + schoolId, "ADMIN", null);
            var sections = classes(caller);                                      // the order matters: the three below name a class
            int teachers = teachers(caller);
            int assignments = assignments(caller, sections.byName());
            int children = children(caller, sections.byName());
            var counts = new Counts(sections.created(), teachers, assignments, children);
            log.info("school seed {} ready: {} new classes, {} new teachers, {} new assignments, {} new children",
                    schoolId, counts.classes(), counts.teachers(), counts.assignments(), counts.children());
            return counts;
        } finally { tenant.clear(); }
    }

    // ---------------------------------------------------------------- the four files

    /** The sections, by lower-cased name: `assignments.csv` and `children.csv` name a class and nothing else. */
    private Sections classes(Principals.User caller) {
        var byKey = new LinkedHashMap<String, String>();
        for (var k : sections.list(null, null)) byKey.put(key(k.curriculum(), k.grade(), k.name()), k.id());
        var byName = new LinkedHashMap<String, String>();
        int created = 0;
        for (var row : rows("classes.csv", 3)) {
            String curriculum = row.at(0), name = row.at(2);
            int grade = row.number(1);
            String id = byKey.get(key(curriculum, grade, name));
            if (id == null) {
                id = row.attempt(() -> sections.create(caller, new ClassDto.CreateSectionRequest(curriculum, grade, name)).id());
                created++;
            }
            if (byName.put(name.toLowerCase(Locale.ROOT), id) != null) throw row.bad(name + " is named twice");
        }
        log.info("school seed: {} classes, {} new", byName.size(), created);
        return new Sections(byName, created);
    }

    /** One bcrypt for the shared password, not one per teacher: the hash is the same string for all of them. */
    private int teachers(Principals.User caller) {
        var seed = props.seed();
        String shared = seed == null ? null : seed.staffPassword();
        String hash = shared == null || shared.isBlank() ? null : encoder.encode(shared);
        var known = new LinkedHashSet<String>();
        for (var t : staff.list()) known.add(t.email().toLowerCase(Locale.ROOT));
        int created = 0;
        for (var row : rows("teachers.csv", 4)) {
            String fullName = row.at(0), email = row.at(1).toLowerCase(Locale.ROOT), curriculum = row.at(3);
            var subjects = Arrays.stream(row.at(2).split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            if (!known.add(email)) continue;
            var made = row.attempt(() -> staff.create(caller, new ClassDto.CreateTeacherRequest(fullName, email, subjects, curriculum, null)));
            if (hash != null) signInReady(made.teacher().userId(), hash);
            created++;                                                          // the one-time password is dropped here, unlogged
        }
        log.info("school seed: {} teachers, {} new", known.size(), created);
        log.info(hash == null
                ? STAFF_PASSWORD_ENV + " unset → passwords not printable; hand one out from Admin › Teachers"
                : "seeded teachers share the password in " + STAFF_PASSWORD_ENV);
        return created;
    }

    /** The whole set a teacher should hold, written once per teacher and only when it is not already hers. */
    private int assignments(Principals.User caller, Map<String, String> classIds) {
        var wanted = new LinkedHashMap<String, List<ClassDto.AssignmentInput>>();
        var taken = new LinkedHashMap<String, String>();
        for (var row : rows("assignments.csv", 3)) {
            String email = row.at(0).toLowerCase(Locale.ROOT), subject = row.at(2);
            String classId = classIds.get(row.at(1).toLowerCase(Locale.ROOT));
            if (classId == null) throw row.bad("no class is named " + row.at(1));
            String holder = taken.putIfAbsent(classId + "/" + subject, email);
            if (holder != null) throw row.bad(row.at(1) + " · " + subject + " is already " + holder + "'s");
            wanted.computeIfAbsent(email, k -> new ArrayList<>()).add(new ClassDto.AssignmentInput(classId, subject));
        }
        var accounts = new LinkedHashMap<String, ClassDto.TeacherAccount>();
        for (var t : staff.list()) accounts.put(t.email().toLowerCase(Locale.ROOT), t);
        int created = 0;
        for (var entry : wanted.entrySet()) {
            var account = accounts.get(entry.getKey());
            if (account == null) throw ApiException.badRequest("seed/assignments.csv: no teacher " + entry.getKey());
            var held = account.assignments().stream().map(a -> a.classId() + "/" + a.subject()).collect(Collectors.toSet());
            var want = entry.getValue().stream().map(a -> a.classId() + "/" + a.subject()).collect(Collectors.toSet());
            if (held.equals(want)) continue;
            staff.setAssignments(caller, account.userId(), new ClassDto.AssignmentsRequest(entry.getValue()));
            created += entry.getValue().size();
        }
        log.info("school seed: {} assignments, {} new", taken.size(), created);
        return created;
    }

    private int children(Principals.User caller, Map<String, String> classIds) {
        var rostered = new LinkedHashMap<String, Set<String>>();
        int created = 0, total = 0;
        for (var row : rows("children.csv", 3)) {
            String classId = classIds.get(row.at(0).toLowerCase(Locale.ROOT));
            if (classId == null) throw row.bad("no class is named " + row.at(0));
            String name = row.at(1), parentEmail = row.at(2).isEmpty() ? null : row.at(2);
            var names = rostered.computeIfAbsent(classId, id -> rosters.list(caller, id).stream()
                    .map(c -> c.name().toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(LinkedHashSet::new)));
            total++;
            if (!names.add(name.toLowerCase(Locale.ROOT))) continue;
            row.attempt(() -> rosters.add(caller, classId, new ClassDto.CreateRosterChildRequest(name, parentEmail, null)));
            created++;
        }
        log.info("school seed: {} children, {} new", total, created);
        return created;
    }

    /** The shared password and no forced change: an e2e run signs in as a seeded teacher without a first-login dance. */
    private void signInReady(String userId, String hash) {
        users.findById(userId).ifPresent(u -> {
            u.setPasswordHash(hash); u.setMustChangePassword(false); u.setUpdatedAt(Instant.now());
            users.save(u);
        });
    }

    // ---------------------------------------------------------------- the files themselves

    /** A data row and where it came from, so every refusal below names the file and the line an editor shows. */
    record Row(String file, int line, List<String> values) {
        String at(int column) { return values.get(column); }
        int number(int column) {
            try { return Integer.parseInt(at(column)); } catch (NumberFormatException e) { throw bad(at(column) + " is not a number"); }
        }
        ApiException bad(String why) { return ApiException.badRequest("seed/" + file + " line " + line + ": " + why); }
        /** A service refusal is this row's fault; the line number is what turns it into something fixable. */
        <T> T attempt(Supplier<T> call) {
            try { return call.get(); } catch (ApiException e) { throw bad(e.getMessage()); }
        }
    }

    private static List<Row> rows(String file, int columns) {
        try (InputStream in = new ClassPathResource("seed/" + file).getInputStream()) {
            return parse(file, new String(in.readAllBytes(), StandardCharsets.UTF_8), columns);
        } catch (IOException e) { throw ApiException.badRequest("seed/" + file + " cannot be read: " + e.getMessage()); }
    }

    /** Line 1 is the header; a row with the wrong number of columns, or a blank first column, stops the load. */
    static List<Row> parse(String file, String text, int columns) {
        var out = new ArrayList<Row>();
        var lines = text.split("\n", -1);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            var cells = new ArrayList<String>();
            for (String cell : line.split(",", -1)) cells.add(cell.strip());
            var row = new Row(file, i + 1, List.copyOf(cells));
            if (cells.size() != columns) throw row.bad(columns + " columns expected, " + cells.size() + " found");
            if (cells.getFirst().isEmpty()) throw row.bad("the first column is empty");
            out.add(row);
        }
        return out;
    }

    private static String key(String curriculum, int grade, String name) {
        return curriculum.toLowerCase(Locale.ROOT) + "/" + grade + "/" + name.toLowerCase(Locale.ROOT);
    }
}
