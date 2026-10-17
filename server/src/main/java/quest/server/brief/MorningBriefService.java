package quest.server.brief;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import quest.api.staff.AttendanceRequestStatus;
import quest.server.ai.AiFailure;
import quest.server.ai.AiInputGuard;
import quest.server.ai.AiService;
import quest.server.ai.AiTask;
import quest.server.ai.AiVerdict;
import quest.server.analysis.Prompts;
import quest.server.attendance.AttendanceRepository;
import quest.server.auth.Principals;
import quest.server.brief.Entities.MorningBriefEntity;
import quest.server.broadcasts.BroadcastService;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.dashboard.TodayService;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.platform.SchoolCalendar;
import quest.server.staffattendance.AttendanceBoardService;
import quest.server.staffattendance.AttendanceRequestService;
import quest.server.staffattendance.AttendanceSettingsService;
import quest.server.staffattendance.RemoteStudyDayService;
import quest.server.staffattendance.StaffAttendanceDto;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.ManagerScope;

/**
 * Feature 25: <strong>the server counts; the model only phrases.</strong>
 *
 * <p>Every number in a brief is computed here out of what features 16, 17, 18, 12 and 04 already store, through the
 * service that already owns that feature and already narrows it to the caller — {@link AttendanceBoardService#tally}
 * for yesterday's staff day (so a manager's brief counts her academy and the Admin's the school),
 * {@link AttendanceRequestService#toDecide} for what waits for her decision, {@link TodayService#overdueComplaints}
 * for the old complaints, {@link BroadcastService#liveEventsFor} for today's events, and the register itself for the
 * children's rate. That is feature 21's rule and it matters more here: a brief that disagreed with the cards under it
 * would be worse than no brief.
 *
 * <p><strong>What the model is given is counts and labels, and nothing else.</strong> No child, parent or employee is
 * named to the provider, which is the one privacy rule this feature has — it is true by construction, because the
 * facts are numbers and every label is one of ours: a remote study day is sent as "today is a remote study day" and
 * the reason a leader typed for it stays on the server, where she reads it on the brief's own facts.
 * {@link AiInputGuard#numbersOnly()} is kept on anyway so that a label a school typed into its own data cannot carry
 * an address or a telephone number out.
 *
 * <p><strong>The numbers are checked, not trusted.</strong> The validator rejects a line carrying any number the
 * counted facts do not hold (Arabic-Indic digits included — the Arabic lines are written in them) and a line too
 * long for a glance. The two dates are allowed whole and never by their parts, so a month cannot be read back as a
 * count ({@link #allowedNumbers}). After two rejected attempts, or a provider that failed, the brief is the deterministic bilingual template
 * built from the very same facts and {@code source} says {@code template}. <em>There is no path on which an invented
 * number reaches a leader.</em>
 *
 * <p><strong>Written once a morning.</strong> {@link MorningBriefRepository#claim} inserts the template row against
 * V47's unique key before the model is called, so the run that wrote the row is the only one that calls the model and
 * a second instance — or a reloaded Home — reads what is there. The model's wording replaces the template in place
 * when it is accepted, which is also why a leader who looks between the claim and the answer sees the template rather
 * than an empty Home.
 */
@Service
public class MorningBriefService {
    private static final Logger log = LoggerFactory.getLogger(MorningBriefService.class);

    /** The feature name the ledger and `LlmFailures` file this call under. */
    public static final String FEATURE = FlagKeys.MORNING_BRIEF;
    /** §6: a brief is a glance. Three lines is the floor, five the ceiling, and a line is one short sentence. */
    public static final int MIN_LINES = 3, MAX_LINES = 5, MAX_LINE = 110;
    /** `(present + late) / marked`, {@link quest.server.attendance.AttendanceService}'s own formula (and RM1's). */
    private static final Set<String> PRESENT = Set.of("PRESENT", "LATE");
    /** ASCII and Arabic-Indic digits: the Arabic lines are written in the second kind. */
    private static final Pattern DIGITS = Pattern.compile("[0-9٠-٩]{4}-[0-9٠-٩]{2}-[0-9٠-٩]{2}|[0-9٠-٩]+");

    private final MorningBriefRepository briefs; private final AttendanceBoardService board;
    private final AttendanceRequestService requests; private final TodayService today;
    private final BroadcastService broadcasts; private final AttendanceRepository register;
    private final RemoteStudyDayService remoteStudyDays; private final AttendanceSettingsService attendanceSettings;
    private final ManagerScope managers; private final FeatureFlags flags; private final SchoolCalendar calendar;
    private final AiService ai; private final Json json; private final Clock clock; private final TransactionTemplate tx;

    public MorningBriefService(MorningBriefRepository briefs, AttendanceBoardService board,
                              AttendanceRequestService requests, TodayService today, BroadcastService broadcasts,
                              AttendanceRepository register, RemoteStudyDayService remoteStudyDays,
                              AttendanceSettingsService attendanceSettings,
                              ManagerScope managers, FeatureFlags flags,
                              SchoolCalendar calendar, AiService ai, Json json, Clock clock,
                              PlatformTransactionManager transactions) {
        this.briefs = briefs; this.board = board; this.requests = requests; this.today = today;
        this.broadcasts = broadcasts; this.register = register; this.remoteStudyDays = remoteStudyDays;
        this.attendanceSettings = attendanceSettings;
        this.managers = managers; this.flags = flags;
        this.calendar = calendar; this.ai = ai; this.json = json; this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * <strong>A teaching day.</strong> The week decides the days of the week ({@link SchoolCalendar.Week}); feature
     * 14's declared holidays decide the rest, and they have to be read here as well — "a day the school does not
     * teach has no brief" is false of an Eid week otherwise, and the day before one is not what yesterday's numbers
     * are about. Read off `attendance_holidays`, which is where the Admin declares them.
     */
    boolean teachingDay(String schoolId, SchoolCalendar.Week week, LocalDate day) {
        return week.isSchoolDay(day) && !holiday(schoolId, day);
    }

    private boolean holiday(String schoolId, LocalDate day) {
        try {
            for (var h : attendanceSettings.holidays(schoolId))
                if (!day.isBefore(h.from()) && !day.isAfter(h.to())) return true;
        } catch (RuntimeException e) { return false; }   // a school whose settings cannot be read still gets a brief
        return false;
    }

    // ---------------------------------------------------------------- GET /me/morning-brief

    /**
     * Today's brief for this leader, built on the spot when {@link MorningBriefJob} has not reached her yet.
     *
     * @return null on a day the school does not teach, which the controller answers as `204 No Content` — a school
     *         that is closed has no morning to brief, and an empty list of lines would read as "nothing needs you".
     */
    public MorningBriefDto.View brief(String schoolId, Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        var week = calendar.of(schoolId);
        LocalDate day = LocalDate.ofInstant(clock.instant(), week.zone());
        if (!teachingDay(schoolId, week, day)) return null;
        var existing = briefs.findOneBySchoolIdAndUserIdAndDay(schoolId, caller.userId(), day);
        return view(existing.orElseGet(() -> generate(schoolId, caller, day, week).row));
    }

    // ---------------------------------------------------------------- the job's own call

    /**
     * One leader's brief, written if nobody has written it. Answers true when <em>this</em> call claimed the row, so
     * {@link MorningBriefJob} rings her bell exactly once however many runs and instances pass through.
     */
    boolean writeIfAbsent(String schoolId, Principals.User caller, LocalDate day) {
        if (briefs.findOneBySchoolIdAndUserIdAndDay(schoolId, caller.userId(), day).isPresent()) return false;
        return generate(schoolId, caller, day, calendar.of(schoolId)).claimed;
    }

    // ---------------------------------------------------------------- facts, claim, phrasing

    /** The claim decides who calls the model; the loser reads what the winner stored. */
    private static final class Written {
        private final MorningBriefEntity row; private final boolean claimed;
        Written(MorningBriefEntity row, boolean claimed) { this.row = row; this.claimed = claimed; }
    }

    private Written generate(String schoolId, Principals.User caller, LocalDate day, SchoolCalendar.Week week) {
        var facts = facts(schoolId, caller, day, week);
        var template = template(facts);
        var row = new MorningBriefEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId); row.setUserId(caller.userId());
        row.setDay(day); row.setFactsJson(json.write(facts));
        row.setTextAr(join(template.ar())); row.setTextEn(join(template.en()));
        row.setSource(MorningBriefDto.SOURCE_TEMPLATE); row.setCreatedAt(clock.instant());
        if (briefs.claim(row) != 1)                                               // another run (or instance) has it
            return new Written(briefs.findOneBySchoolIdAndUserIdAndDay(schoolId, caller.userId(), day).orElse(row), false);

        var phrased = phrase(schoolId, facts, day);
        if (phrased == null) return new Written(row, true);                        // the template is already stored
        row.setTextAr(join(phrased.ar())); row.setTextEn(join(phrased.en()));
        row.setSource(MorningBriefDto.SOURCE_AI);
        tx.executeWithoutResult(t -> briefs.save(row));
        return new Written(row, true);
    }

    /**
     * What the brief is about. A feature that is switched off contributes a zero rather than a guess: with
     * `staffAttendance` off there is no staff day and nothing waiting, with `chat` off no complaints, with
     * `announcements` off no events and with `remoteStudyDays` off no remote study day — the same reading feature
     * 21's cards take. Each switch is read by the service that owns it, never second-guessed here.
     */
    MorningBriefDto.Facts facts(String schoolId, Principals.User caller, LocalDate day, SchoolCalendar.Week week) {
        LocalDate yesterday = previousSchoolDay(schoolId, week, day);
        boolean attendance = flags.isOn(schoolId, FlagKeys.STAFF_ATTENDANCE);
        var tally = attendance ? board.tally(schoolId, caller, yesterday) : new AttendanceBoardService.DayTally(0, 0, 0);
        int waiting = attendance ? requests.toDecide(caller, AttendanceRequestStatus.PENDING).getRequests().size() : 0;
        int events = flags.isOn(schoolId, FlagKeys.ANNOUNCEMENTS) ? broadcasts.liveEventsFor(caller) : 0;
        var remote = remoteStudyDays.forLeader(schoolId, caller, day);
        String scope = ManagerScope.ROLE.equals(caller.role()) ? StaffAttendanceDto.SCOPE_ACADEMY : StaffAttendanceDto.SCOPE_SCHOOL;
        return new MorningBriefDto.Facts(scope, yesterday.toString(), tally.late(), tally.absent(),
                tally.missingCheckout(), waiting, today.overdueComplaints(caller), events,
                childAttendanceRate(caller, yesterday), remote.on(), remote.reason());
    }

    /**
     * The teaching day before this one, which is what the staff numbers are about — never a Friday's empty board,
     * and never a declared holiday's either: on the first morning back from Eid, "yesterday" is the last day the
     * school actually taught. Thirty days back at most, so a school on a long closure cannot loop.
     */
    private LocalDate previousSchoolDay(String schoolId, SchoolCalendar.Week week, LocalDate day) {
        LocalDate d = day.minusDays(1);
        for (int i = 0; i < 30 && !teachingDay(schoolId, week, d); i++) d = d.minusDays(1);
        return d;
    }

    /**
     * `(present + late) / marked` over yesterday's registers in her reach, as a whole percentage, or <strong>null
     * when no register was taken</strong> — a missing number, never a zero, because "0 %" would read as a school
     * nobody came to. {@link ManagerScope#sectionsOf} is the reach: her department's sections for a manager, every
     * section for the Admin. One grouped statement, whatever the school holds.
     */
    private Integer childAttendanceRate(Principals.User caller, LocalDate day) {
        var sectionIds = managers.sectionsOf(caller).stream().map(ClassEntity::getId).toList();
        if (sectionIds.isEmpty()) return null;
        long marked = 0, present = 0;
        for (Object[] row : register.countByStatusInWindow(sectionIds, day, day)) {
            long rows = ((Number) row[2]).longValue();
            marked += rows;
            if (PRESENT.contains(String.valueOf(row[1]).toUpperCase(Locale.ROOT))) present += rows;
        }
        return marked == 0 ? null : (int) Math.round(present * 100.0 / marked);
    }

    // ---------------------------------------------------------------- the model and its answer

    /** The two lists, same things in the same order. */
    record Lines(List<String> ar, List<String> en) {}

    /** The model's wording, or null when there is none to trust — the caller already stored the template. */
    private Lines phrase(String schoolId, MorningBriefDto.Facts facts, LocalDate day) {
        String user = Prompts.userF(day.toString(), scopeLabel(facts.scope()), promptFacts(facts));
        Set<String> allowed = allowedNumbers(facts, day);
        try {
            JsonNode accepted = ai.run(AiTask.of(FEATURE, "F-" + schoolId, schoolId, Prompts.SYSTEM_F, user)
                    .reading((raw, attempt) -> {
                        JsonNode candidate;
                        try { candidate = json.tree(raw); }
                        catch (RuntimeException e) { return AiVerdict.rejected("the answer was not JSON at all"); }
                        var wrong = problems(candidate, allowed);
                        return wrong.isEmpty() ? AiVerdict.ok(candidate) : AiVerdict.rejected(wrong);
                    })
                    .retrying(AiTask.Retry.VALIDATOR)
                    .guardedBy(AiInputGuard.numbersOnly())
                    .keepingRejects()).json();
            return new Lines(lines(accepted, "linesAr"), lines(accepted, "linesEn"));
        } catch (AiFailure failed) {
            // Never a 502: a brief is not something a leader asked for, and the template says the same facts.
            log.info("morning brief: keeping the template for {} ({}): {}", schoolId, failed.kind(), failed.getMessage());
            return null;
        }
    }

    /** Everything wrong with one answer, in the words the second attempt is given. Empty means it may be stored. */
    static List<String> problems(JsonNode answer, Set<String> allowed) {
        if (!answer.isObject()) return List.of("answer with one JSON object holding \"linesAr\" and \"linesEn\"");
        var wrong = new ArrayList<String>();
        JsonNode ar = answer.path("linesAr"), en = answer.path("linesEn");
        checkLines(wrong, "linesAr", ar, allowed);
        checkLines(wrong, "linesEn", en, allowed);
        if (ar.isArray() && en.isArray() && ar.size() != en.size())
            wrong.add("\"linesAr\" and \"linesEn\" must hold the same number of lines, in the same order");
        return List.copyOf(wrong);
    }

    private static void checkLines(List<String> wrong, String field, JsonNode list, Set<String> allowed) {
        if (!list.isArray()) { wrong.add("\"" + field + "\" is required and must be an array of lines"); return; }
        if (list.size() < MIN_LINES || list.size() > MAX_LINES)
            wrong.add("\"" + field + "\" must hold between " + MIN_LINES + " and " + MAX_LINES + " lines, not " + list.size());
        for (JsonNode line : list) {
            if (!line.isTextual() || line.asText().isBlank()) { wrong.add("every line of \"" + field + "\" must be text"); continue; }
            String text = line.asText().strip();
            if (text.length() > MAX_LINE)
                wrong.add("this line of \"" + field + "\" is longer than " + MAX_LINE + " characters: " + text);
            for (String number : numbersIn(text))
                if (!allowed.contains(number))
                    wrong.add("the number " + number + " is not one of the facts you were given, in: " + text);
        }
    }

    /**
     * <strong>The numbers a line may hold:</strong> the counted facts themselves, and the two dates — each whole.
     * Reading them off the rendered prompt instead, as this used to, made a fact of every digit in a date: on
     * 2026-10-16 the year, the month and both days counted, so "10 employees were absent yesterday" passed with two
     * absences. A date is therefore matched as one token ({@link #DIGITS}) and its parts are not numbers at all.
     */
    static Set<String> allowedNumbers(MorningBriefDto.Facts f, LocalDate today) {
        var out = new LinkedHashSet<String>();
        for (int n : new int[] {f.lateYesterday(), f.absentYesterday(), f.missingCheckoutYesterday(),
                f.requestsWaiting(), f.overdueComplaints(), f.eventsToday()}) out.add(String.valueOf(n));
        if (f.childAttendanceRate() != null) out.add(String.valueOf(f.childAttendanceRate()));
        out.add(f.yesterday()); out.add(today.toString());          // the dates, whole: "2026-10-15", never "10"
        return Set.copyOf(out);
    }

    /**
     * Every number in a text, as a comparable string: Arabic-Indic digits folded to ASCII and leading zeros dropped,
     * so "٠٢" and "2" are the same number and a model that writes the Arabic lines in Arabic numerals is not rejected
     * for it. A written date is one token rather than three numbers, for {@link #allowedNumbers}'s reason.
     */
    static Set<String> numbersIn(String text) {
        var out = new LinkedHashSet<String>();
        var m = DIGITS.matcher(text);
        while (m.find()) out.add(plain(m.group()));
        return out;
    }

    private static String plain(String run) {
        var digits = new StringBuilder(run.length());
        for (int i = 0; i < run.length(); i++) {
            char c = run.charAt(i);
            digits.append(c >= '٠' && c <= '٩' ? (char) ('0' + c - '٠') : c);
        }
        int from = 0;
        while (from < digits.length() - 1 && digits.charAt(from) == '0') from++;
        return digits.substring(from);
    }

    private static List<String> lines(JsonNode answer, String field) {
        var out = new ArrayList<String>();
        answer.path(field).forEach(line -> out.add(line.asText().strip()));
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- what the model is told

    private static String scopeLabel(String scope) {
        return StaffAttendanceDto.SCOPE_ACADEMY.equals(scope) ? "one academy of the school" : "the whole school";
    }

    /** The facts as the model reads them: a label and a number per line, never a name. */
    static List<String> promptFacts(MorningBriefDto.Facts f) {
        var out = new ArrayList<String>();
        out.add("on " + f.yesterday() + ", the last teaching day: " + f.lateYesterday() + " employees arrived late, "
                + f.absentYesterday() + " were absent, " + f.missingCheckoutYesterday() + " did not check out");
        out.add(f.requestsWaiting() + " attendance requests are waiting for her decision");
        out.add(f.overdueComplaints() + " complaints have been open for more than one day");
        // A fixed label, never the reason: the reason is free text a leader typed and "no employee is named to the
        // provider" would be untrue the first time one of them reads "Mrs Nada is in hospital". The leader still
        // reads it — it is on the facts the endpoint answers with, and on the dashboard's card.
        if (f.remoteStudyDayToday()) out.add("today is a remote study day");
        if (f.eventsToday() > 0) out.add(f.eventsToday() + " events are live for parents today");
        if (f.childAttendanceRate() != null)
            out.add("the children's attendance on " + f.yesterday() + " was " + f.childAttendanceRate() + " per cent");
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- the fallback, from the same facts

    /**
     * The brief with no model in it: the same facts, in the plainest sentence each one has, in both languages. It is
     * what a leader reads when the provider is down or invented a number — three to five lines either way, so the
     * screen does not change shape with the weather.
     */
    static Lines template(MorningBriefDto.Facts f) {
        var ar = new ArrayList<String>();
        var en = new ArrayList<String>();
        en.add("Yesterday: " + f.lateYesterday() + " late, " + f.absentYesterday() + " absent, "
                + f.missingCheckoutYesterday() + " without a check-out.");
        ar.add("أمس: " + f.lateYesterday() + " متأخرون، " + f.absentYesterday() + " غائبون، "
                + f.missingCheckoutYesterday() + " بلا تسجيل انصراف.");
        en.add(f.requestsWaiting() == 0 ? "No request is waiting for your decision."
                : f.requestsWaiting() == 1 ? "1 request is waiting for your decision."
                : f.requestsWaiting() + " requests are waiting for your decision.");
        ar.add(f.requestsWaiting() == 0 ? "لا توجد طلبات تنتظر قرارك."
                : f.requestsWaiting() + " من الطلبات تنتظر قرارك.");
        en.add(f.overdueComplaints() == 0 ? "No complaint has been open for more than a day."
                : f.overdueComplaints() == 1 ? "1 complaint has been open for more than a day."
                : f.overdueComplaints() + " complaints have been open for more than a day.");
        ar.add(f.overdueComplaints() == 0 ? "لا شكوى مفتوحة أكثر من يوم."
                : f.overdueComplaints() + " من الشكاوى مفتوحة أكثر من يوم.");
        // The three above are always said. Below them, in the order a leader would want them, and cut at
        // MAX_LINES so the template is the same shape as an accepted answer — the school being closed first,
        // because it changes what every other line means.
        if (f.remoteStudyDayToday()) {
            en.add("Today is a remote study day.");
            ar.add("اليوم يوم دراسة عن بعد.");
        }
        if (f.childAttendanceRate() != null) {
            en.add("Children's attendance yesterday: " + f.childAttendanceRate() + "%.");
            ar.add("حضور الطلاب أمس: " + f.childAttendanceRate() + "٪.");
        }
        if (f.eventsToday() > 0) {
            en.add(f.eventsToday() == 1 ? "1 event is live for parents today."
                    : f.eventsToday() + " events are live for parents today.");
            ar.add(f.eventsToday() + " من الأحداث معلنة لأولياء الأمور اليوم.");
        }
        return new Lines(List.copyOf(ar.subList(0, Math.min(MAX_LINES, ar.size()))),
                List.copyOf(en.subList(0, Math.min(MAX_LINES, en.size()))));
    }

    // ---------------------------------------------------------------- storage ↔ wire

    private static final String SEPARATOR = "\n";

    private static String join(List<String> lines) { return String.join(SEPARATOR, lines); }

    private static List<String> split(String stored) {
        if (stored == null || stored.isBlank()) return List.of();
        return stored.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    private MorningBriefDto.View view(MorningBriefEntity row) {
        return new MorningBriefDto.View(row.getDay().toString(), split(row.getTextAr()), split(row.getTextEn()),
                row.getSource(), json.read(row.getFactsJson(), MorningBriefDto.Facts.class));
    }
}
