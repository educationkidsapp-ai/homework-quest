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
 * <p><strong>Two profiles.</strong> `quest.seed.profile` (SEED_PROFILE) picks which four files are read: `full`
 * (the default, `resources/seed/`) is the school above, which the automated e2e suite needs; `acceptance`
 * (`resources/seed/acceptance/`) is the owner's own environment — three sections, two teachers, three assignments and
 * no children at all, because the children arrive when he registers as a parent in the app and are attached to a
 * section with `POST /admin/classes/{id}/roster/attach`. Everything below is the same either way: the same reconcile,
 * the same matching on a lower-cased email, the same shared password.
 *
 * <p><strong>The files are the truth, and a broken one is not an outage.</strong> QA is not re-created between
 * deploys, so a run whose `assignments.csv` has moved a slot meets the school still holding the old one: the seed
 * reconciles what the seeded teachers teach to the file rather than adding to it (see {@link #assignments}). And
 * because this is a `CommandLineRunner`, every phase is caught: a fixture nobody can load costs QA its seed, never
 * its revision.
 *
 * <p><strong>Passwords.</strong> With SEED_STAFF_PASSWORD set, every teacher named in `teachers.csv` gets that one
 * password with `must_change_password` cleared — the ones this run creates and the ones an earlier run did, because
 * QA is usually seeded before the secret exists and being already seeded is no reason to be unable to sign in. The
 * value is never logged. Without it nothing touches a password at all: a new teacher keeps the one-time password
 * {@link TemporaryPasswords} generated, an existing one keeps whatever she has, and an Admin hands a fresh one out
 * with `POST /admin/teachers/{id}/password`.
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
        log.info("school seed: the {} profile, from {}", seed.profileOrFull(), seed.directory());
        load(TenantContext.DEFAULT_SCHOOL, seed.staffPassword(), true);
    }

    /**
     * Loads the four files into one school. The scope is set the way an Admin who picked that school with
     * `X-School-Id` sets it, so every query underneath is filtered by it exactly as it is during a request.
     */
    public Counts load(String schoolId) {
        var seed = props.seed();
        return load(schoolId, seed == null ? null : seed.staffPassword());
    }

    /**
     * The same load with the staff password given rather than configured. QA seeds itself before SEED_STAFF_PASSWORD
     * exists as often as not, so the password is applied to the teachers a previous run created too — being already
     * seeded is not a reason to be unable to sign in.
     */
    public Counts load(String schoolId, String staffPassword) { return load(schoolId, staffPassword, false); }

    /**
     * The same load again, and with `lenient` — which is how {@link #run} starts the server — a phase that throws is
     * logged with what the load managed and the rest goes on regardless. A fixture somebody mis-edited is a QA
     * nuisance; a `CommandLineRunner` that rethrows turns it into a revision that never answers `/health`. A caller
     * that asked for the load itself still gets the refusal, so the tests below can still read it.
     */
    Counts load(String schoolId, String staffPassword, boolean lenient) {
        return load(schoolId, staffPassword, lenient, props.seed() == null ? "seed/" : props.seed().directory());
    }

    /** The same load with the directory named rather than configured — how the tests load one profile per school. */
    Counts load(String schoolId, String staffPassword, boolean lenient, String dir) {
        tenant.set("ADMIN", null, schoolId);
        try {
            var caller = new Principals.User(ACTOR, ACTOR + "@" + schoolId, "ADMIN", null);
            var sections = phase("classes", lenient, () -> classes(caller, dir), new Sections(Map.of(), 0));  // the order matters: the three below name a class
            int teachers = phase("teachers", lenient, () -> teachers(caller, staffPassword, dir), 0);
            int assignments = phase("assignments", lenient, () -> assignments(caller, sections.byName(), dir), 0);
            int children = phase("children", lenient, () -> children(caller, sections.byName(), dir), 0);
            var counts = new Counts(sections.created(), teachers, assignments, children);
            log.info("school seed {} ready: {} new classes, {} new teachers, {} new assignments, {} new children",
                    schoolId, counts.classes(), counts.teachers(), counts.assignments(), counts.children());
            return counts;
        } finally { tenant.clear(); }
    }

    /** One phase: rethrown for a caller that asked for the load, logged and skipped while the server is starting. */
    private <T> T phase(String name, boolean lenient, Supplier<T> work, T skipped) {
        try { return work.get(); } catch (RuntimeException e) {
            if (!lenient) throw e;
            log.error("school seed: the {} phase failed and was skipped, the load goes on: {}", name, e.getMessage(), e);
            return skipped;
        }
    }

    // ---------------------------------------------------------------- the four files

    /** The sections, by lower-cased name: `assignments.csv` and `children.csv` name a class and nothing else. */
    private Sections classes(Principals.User caller, String dir) {
        var byKey = new LinkedHashMap<String, String>();
        for (var k : sections.list(null, null)) byKey.put(key(k.curriculum(), k.grade(), k.name()), k.id());
        var byName = new LinkedHashMap<String, String>();
        int created = 0;
        for (var row : rows(dir, "classes.csv", 3)) {
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

    /**
     * One bcrypt for the shared password, not one per teacher: the hash is the same string for all of them, and it is
     * put on the teachers a previous run created as well as on the new ones. With no password configured nothing
     * touches an existing account — a teacher who has since chosen her own password keeps it.
     */
    private int teachers(Principals.User caller, String staffPassword, String dir) {
        String hash = staffPassword == null || staffPassword.isBlank() ? null : encoder.encode(staffPassword);
        var known = new LinkedHashMap<String, String>();                        // email -> user id
        for (var t : staff.list()) known.put(t.email().toLowerCase(Locale.ROOT), t.userId());
        int created = 0, signable = 0;
        for (var row : rows(dir, "teachers.csv", 4)) {
            String fullName = row.at(0), email = row.at(1).toLowerCase(Locale.ROOT), curriculum = row.at(3);
            var subjects = Arrays.stream(row.at(2).split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            String userId = known.get(email);
            if (userId == null) {
                var made = row.attempt(() -> staff.create(caller, new ClassDto.CreateTeacherRequest(fullName, email, subjects, curriculum, null)));
                userId = made.teacher().userId();                               // the one-time password is dropped here, unlogged
                known.put(email, userId);
                created++;
            }
            if (hash == null) continue;
            signInReady(userId, hash);
            signable++;
        }
        log.info("school seed: {} teachers, {} new", known.size(), created);
        log.info(hash == null
                ? STAFF_PASSWORD_ENV + " unset → passwords not printable; hand one out from Admin › Teachers"
                : "school seed: " + signable + " teachers carry the password in " + STAFF_PASSWORD_ENV);
        return created;
    }

    /**
     * `assignments.csv` is the truth about what a <em>seeded</em> teacher teaches, not merely a list of rows to add.
     * A slot the file moves from one seeded colleague to another is taken off the first before it is asked of the
     * second — the whole desired map is worked out up front and the stale halves are written first — so the "one
     * teacher per subject per class" rule never sees the two of them holding it at once and an edited fixture cannot
     * meet QA as a 409. A seeded teacher the file no longer names ends with nothing; a slot held by a teacher an
     * Admin created by hand is hers, and the seed says so at WARN and leaves both of them alone.
     */
    private int assignments(Principals.User caller, Map<String, String> classIds, String dir) {
        var seeded = new LinkedHashSet<String>();
        for (var row : rows(dir, "teachers.csv", 4)) seeded.add(row.at(1).toLowerCase(Locale.ROOT));
        var names = new LinkedHashMap<String, String>();                        // class id -> the name the files call it
        classIds.forEach((name, id) -> names.put(id, name));
        var wanted = new LinkedHashMap<String, LinkedHashSet<String>>();        // email -> the slots the file gives her
        var slots = new LinkedHashMap<String, String>();                        // slot -> the email the file gives it to
        for (var row : rows(dir, "assignments.csv", 3)) {
            String email = row.at(0).toLowerCase(Locale.ROOT), subject = row.at(2);
            String classId = classIds.get(row.at(1).toLowerCase(Locale.ROOT));
            if (classId == null) throw row.bad("no class is named " + row.at(1));
            String holder = slots.putIfAbsent(slot(classId, subject), email);
            if (holder != null) throw row.bad(row.at(1) + " · " + subject + " is already " + holder + "'s");
            wanted.computeIfAbsent(email, k -> new LinkedHashSet<>()).add(slot(classId, subject));
        }
        var accounts = new LinkedHashMap<String, ClassDto.TeacherAccount>();
        var held = new LinkedHashMap<String, String>();                         // slot -> the email teaching it now
        for (var t : staff.list()) {
            String email = t.email().toLowerCase(Locale.ROOT);
            accounts.put(email, t);
            for (var a : t.assignments()) held.put(slot(a.classId(), a.subject()), email);
        }
        for (var email : wanted.keySet())
            if (!accounts.containsKey(email)) throw ApiException.badRequest("seed/assignments.csv: no teacher " + email);

        int left = 0;
        for (var slot : List.copyOf(slots.keySet())) {
            String holder = held.get(slot), wants = slots.get(slot);
            if (holder == null || holder.equals(wants) || seeded.contains(holder)) continue;
            log.warn("school seed: {} · {} is taught by {}, who is not in teachers.csv — it stays hers and {} does not get it",
                    names.get(classOf(slot)), subjectOf(slot), holder, wants);
            wanted.get(wants).remove(slot);
            slots.remove(slot);
            left++;
        }
        var desired = new LinkedHashMap<>(wanted);
        for (var email : accounts.keySet()) if (seeded.contains(email)) desired.putIfAbsent(email, new LinkedHashSet<>());

        int added = 0, removed = 0;
        var kept = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (var entry : desired.entrySet()) {                                  // the stale half first: the slot is free before anyone asks for it
            var now = accounts.get(entry.getKey()).assignments().stream().map(a -> slot(a.classId(), a.subject()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            var keep = new LinkedHashSet<>(now);
            keep.retainAll(entry.getValue());
            kept.put(entry.getKey(), keep);
            removed += now.size() - keep.size(); added += entry.getValue().size() - keep.size();
            if (keep.size() < now.size()) write(caller, accounts.get(entry.getKey()).userId(), keep);
        }
        for (var entry : desired.entrySet())
            if (!entry.getValue().equals(kept.get(entry.getKey()))) write(caller, accounts.get(entry.getKey()).userId(), entry.getValue());
        log.info("school seed: {} assignments, {} new, {} taken off a seeded teacher, {} left with a teacher outside teachers.csv",
                slots.size(), added, removed, left);
        return added;
    }

    /** The complete set she should hold afterwards, through the same call the Admin's screen makes. */
    private void write(Principals.User caller, String userId, Set<String> slots) {
        var inputs = slots.stream().map(s -> new ClassDto.AssignmentInput(classOf(s), subjectOf(s))).toList();
        staff.setAssignments(caller, userId, new ClassDto.AssignmentsRequest(inputs));
    }

    private static String slot(String classId, String subject) { return classId + "/" + subject; }
    private static String classOf(String slot) { return slot.substring(0, slot.lastIndexOf('/')); }
    private static String subjectOf(String slot) { return slot.substring(slot.lastIndexOf('/') + 1); }

    private int children(Principals.User caller, Map<String, String> classIds, String dir) {
        var rostered = new LinkedHashMap<String, Set<String>>();
        int created = 0, total = 0;
        for (var row : rows(dir, "children.csv", 3)) {
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
        ApiException bad(String why) { return ApiException.badRequest("seed/" + file.replaceFirst("^seed/", "") + " line " + line + ": " + why); }
        /** A service refusal is this row's fault; the line number is what turns it into something fixable. */
        <T> T attempt(Supplier<T> call) {
            try { return call.get(); } catch (ApiException e) { throw bad(e.getMessage()); }
        }
    }

    private static List<Row> rows(String dir, String file, int columns) {
        try (InputStream in = new ClassPathResource(dir + file).getInputStream()) {
            return parse(dir + file, new String(in.readAllBytes(), StandardCharsets.UTF_8), columns);
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
