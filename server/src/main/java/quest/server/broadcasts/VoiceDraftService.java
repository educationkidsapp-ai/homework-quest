package quest.server.broadcasts;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import quest.server.analysis.LlmClient;
import quest.server.analysis.LlmFailures;
import quest.server.analysis.Prompts;
import quest.server.auth.Principals;
import quest.server.broadcasts.Entities.AnnouncementDraftEntity;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.platform.SchoolCalendar;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.ManagerScope;
import quest.server.tenancy.TenantContext;

/**
 * Feature 22: <strong>a leader's spoken note becomes an announcement she can read, correct and send.</strong>
 * Prompt E, in {@link quest.server.analysis.StopTextService}'s shape — a strict little schema in the system turn, two
 * attempts with the validator's own errors carried into the second, {@code 422 rephrase} after that, and
 * {@code 502 model_failed} when the provider is the thing that failed.
 *
 * <p><strong>Nothing here touches audio.</strong> Speech to text runs on her device (the browser's Web Speech API,
 * `SpeechRecognizer`, `SFSpeechRecognizer`), so the server is handed text and the spec's "the audio is gone after
 * transcription" is true by construction rather than by a sweep. What the model is given is the transcript, today's
 * date in the school's own zone and the academy-and-grade cells she actually reaches — <strong>no child, parent or
 * employee is named</strong>, which is the one privacy rule the feature has.
 *
 * <p><strong>The server does not trust the suggestion.</strong> An audience is kept only when it matches one of her
 * real cells ({@link ManagerScope#reach}, so the other academy and another school's are already absent), and dropped
 * whole otherwise — a dropped audience means her whole scope, which is what an empty `sectionIds` already means to
 * {@link BroadcastService}. An expiry is kept only when it is in the future and inside the broadcast lifetime.
 *
 * <p><strong>It posts nothing.</strong> The row it writes has no `broadcast_id` and nobody is told about it; Send is
 * the leader pressing `POST /management/broadcasts` with `voiceDraftId`, and an unsent draft is swept after a week
 * ({@link VoiceDraftRetention}).
 *
 * <p>E1's three phases, for {@code StopTextService}'s reason: read what the prompt needs, let the transaction go, call
 * the model, then take one short transaction to store the answer. A model call is seconds to minutes, and ten leaders
 * pressing the button at once must not be the whole connection pool.
 */
@Service
public class VoiceDraftService {
    private static final Logger log = LoggerFactory.getLogger(VoiceDraftService.class);
    /** Two minutes of speech is a few hundred words; this is the ceiling a pasted essay hits. */
    public static final int MAX_TRANSCRIPT = 4000;
    /** `{ "code": "rephrase" }` — the assistant could not produce an announcement that fits, twice. */
    public static final String REPHRASE = "rephrase";
    /** {@link BroadcastService}'s own three numbers, so a draft can never carry what a broadcast would refuse. */
    private static final int MAX_TITLE = 120, MAX_BODY = 1000, MAX_LIFETIME_DAYS = 400;

    private final AnnouncementDraftRepository drafts; private final ManagerScope managers;
    private final SchoolCalendar calendar; private final LlmClient llm; private final Json json;
    private final TenantContext tenant; private final Clock clock; private final TransactionTemplate tx;

    public VoiceDraftService(AnnouncementDraftRepository drafts, ManagerScope managers, SchoolCalendar calendar,
                             LlmClient llm, Json json, TenantContext tenant, Clock clock,
                             PlatformTransactionManager transactions) {
        this.drafts = drafts; this.managers = managers; this.calendar = calendar; this.llm = llm; this.json = json;
        this.tenant = tenant; this.clock = clock; this.tx = new TransactionTemplate(transactions);
    }

    /** One prepared announcement, stored unsent and delivered to nobody. */
    public VoiceDraftDto.View create(Principals.User caller, VoiceDraftDto.CreateRequest request) {
        String transcript = request == null || request.transcript() == null ? "" : request.transcript().strip();
        if (transcript.isEmpty()) throw ApiException.badRequest("Say something first — there is nothing to turn into an announcement.");
        if (transcript.length() > MAX_TRANSCRIPT)
            throw ApiException.badRequest("That is longer than one announcement can hold (" + MAX_TRANSCRIPT + " characters).");
        String schoolId = tenant.writeSchoolId();
        var week = calendar.of(schoolId);
        var cells = cellsOf(caller);

        String system = Prompts.SYSTEM_E;
        // One `schools` read for both the day and the expiry: `SchoolCalendar.today` would ask for the week again.
        String user = Prompts.userE(LocalDate.now(clock.withZone(week.zone())).toString(), labels(cells), transcript);
        List<String> errors = List.of(); JsonNode accepted = null;
        for (int attempt = 0; attempt < 2 && accepted == null; attempt++) {
            String turn = attempt == 0 ? user
                    : user + "\n\nYour previous answer was rejected by the validator:\n- " + String.join("\n- ", errors)
                            + "\nAnswer again with corrected JSON only.";
            LlmClient.Result answer = call(system, turn);
            JsonNode candidate;
            try { candidate = json.tree(answer.text()); }
            catch (RuntimeException e) { errors = List.of("the answer was not JSON at all"); continue; }
            errors = problems(candidate);
            if (errors.isEmpty()) { accepted = candidate; break; }
            log.warn("Prompt E attempt {} invalid: {}", attempt + 1, errors);
            LlmFailures.keep("E-" + schoolId, answer.text(), errors);
        }
        if (accepted == null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, REPHRASE,
                    "Couldn't write that announcement, please say it again. " + String.join("; ", errors));

        var row = new AnnouncementDraftEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId); row.setAuthorId(caller.userId());
        row.setTranscript(transcript);
        row.setTitle(text(accepted, "title", MAX_TITLE));
        row.setBodyAr(text(accepted, "bodyAr", MAX_BODY));
        row.setBodyEn(text(accepted, "bodyEn", MAX_BODY));
        var cell = suggested(accepted.path("audience"), cells);
        if (cell != null) { row.setCurriculum(cell.curriculum()); row.setGrade(cell.grade()); }
        row.setExpiresAt(expiry(accepted.path("expiresOn").asText(null), week.zone()));
        row.setCreatedAt(clock.instant());
        tx.executeWithoutResult(t -> drafts.save(row));
        return view(row);
    }

    /** The row the composer is sending from, or 404 — another author's and another school's are the same answer. */
    AnnouncementDraftEntity requireSendable(String schoolId, String authorId, String draftId) {
        var row = drafts.findOneOfAuthor(schoolId, draftId, authorId).orElseThrow(() -> ApiException.notFound("voice draft"));
        if (row.getBroadcastId() != null)
            throw ApiException.conflict("already_sent", "That announcement has already been sent.");
        return row;
    }

    /** Called inside {@link BroadcastService#managerPost}'s own transaction, so the link and the broadcast are one write. */
    void link(AnnouncementDraftEntity draft, String broadcastId) { draft.setBroadcastId(broadcastId); drafts.save(draft); }

    static VoiceDraftDto.View view(AnnouncementDraftEntity row) {
        var audience = row.getCurriculum() == null && row.getGrade() == null ? null
                : new VoiceDraftDto.Audience(row.getCurriculum(), row.getGrade());
        return new VoiceDraftDto.View(row.getId(), row.getTitle(), row.getBodyAr(), row.getBodyEn(), audience,
                row.getExpiresAt() == null ? null : row.getExpiresAt().toEpochMilli(), row.getTranscript());
    }

    // ---------------------------------------------------------------- what she really reaches

    /** One (academy, grade) the caller actually has a class in. A manager's own department only; the Admin's, the school. */
    record Cell(String curriculum, int grade) {}

    /**
     * Her cells, in reading order. {@link ManagerScope#reach} is the authority: it is already narrowed to her
     * department and the `school` filter has already narrowed it to her school, so a cell of the other academy or of
     * another school cannot appear here and therefore cannot survive the check below.
     */
    private List<Cell> cellsOf(Principals.User caller) {
        Set<Cell> cells = new LinkedHashSet<>();
        for (ClassEntity section : managers.reach(caller).sections())
            if (section.getCurriculum() != null && !section.getCurriculum().isBlank())
                cells.add(new Cell(ManagerScope.normalise(section.getCurriculum()), section.getGrade()));
        return List.copyOf(cells);
    }

    private static List<String> labels(List<Cell> cells) {
        var out = new ArrayList<String>(cells.size());
        for (Cell cell : cells) out.add("curriculum \"" + cell.curriculum() + "\", grade " + cell.grade());
        return out;
    }

    /**
     * The suggestion, or null for "her whole scope". It is kept only when it <em>is</em> one of her cells: a grade the
     * school does not teach, an academy she does not manage and another school's academy are each dropped whole
     * rather than half-corrected, because a preview addressed to a class that does not exist is the one thing the
     * spec says must not happen. A curriculum on its own, or a grade on its own, is kept when some cell carries it.
     */
    private static VoiceDraftDto.Audience suggested(JsonNode audience, List<Cell> cells) {
        if (!audience.isObject()) return null;
        String curriculum = audience.path("curriculum").isTextual() && !audience.get("curriculum").asText().isBlank()
                ? ManagerScope.normalise(audience.get("curriculum").asText()) : null;
        Integer grade = audience.path("grade").isInt() ? audience.get("grade").asInt() : null;
        if (curriculum == null && grade == null) return null;
        for (Cell cell : cells) {
            if (curriculum != null && !curriculum.equals(cell.curriculum())) continue;
            if (grade != null && grade != cell.grade()) continue;
            return new VoiceDraftDto.Audience(curriculum, grade);
        }
        return null;
    }

    /**
     * The end of the day she named, in the school's own zone, or null. Out of the future and beyond the broadcast
     * lifetime are both dropped rather than refused: she said a date out loud, the assistant may have read it wrong,
     * and an announcement with no expiry is the safe reading of "I could not tell".
     */
    private Instant expiry(String day, ZoneId zone) {
        if (day == null || day.isBlank()) return null;
        LocalDate date;
        try { date = LocalDate.parse(day.strip()); } catch (DateTimeParseException e) { return null; }
        Instant at = date.atTime(LocalTime.of(23, 59, 59)).atZone(zone).toInstant();
        Instant now = clock.instant();
        if (!at.isAfter(now) || at.isAfter(now.plus(Duration.ofDays(MAX_LIFETIME_DAYS)))) return null;
        return at;
    }

    // ---------------------------------------------------------------- the model and its answer

    private LlmClient.Result call(String system, String user) {
        try { return llm.complete(system, user, List.of()); }
        catch (LlmClient.LlmException e) {
            if (e.isTransient()) throw new ApiException(HttpStatus.BAD_GATEWAY, quest.api.dto.ApiError.MODEL_FAILED,
                    "The AI is busy — try that again in a moment.");
            throw new ApiException(HttpStatus.BAD_GATEWAY, quest.api.dto.ApiError.MODEL_FAILED, e.getMessage());
        }
    }

    /** Everything wrong with one answer, in the words the second attempt is given. Empty means it may be stored. */
    private static List<String> problems(JsonNode answer) {
        var wrong = new ArrayList<String>();
        if (!answer.isObject()) { wrong.add("answer with one JSON object"); return wrong; }
        required(wrong, answer, "title", MAX_TITLE);
        required(wrong, answer, "bodyAr", MAX_BODY);
        required(wrong, answer, "bodyEn", MAX_BODY);
        JsonNode audience = answer.path("audience");
        if (!audience.isMissingNode() && !audience.isNull() && !audience.isObject())
            wrong.add("\"audience\" must be an object with \"curriculum\" and \"grade\", or be left out");
        JsonNode expires = answer.path("expiresOn");
        if (expires.isTextual() && !expires.asText().isBlank()) {
            try { LocalDate.parse(expires.asText().strip()); }
            catch (DateTimeParseException e) { wrong.add("\"expiresOn\" must be a date written yyyy-MM-dd"); }
        } else if (!expires.isMissingNode() && !expires.isNull()) {
            wrong.add("\"expiresOn\" must be a date written yyyy-MM-dd, or be left out");
        }
        return List.copyOf(wrong);
    }

    private static void required(List<String> wrong, JsonNode answer, String field, int max) {
        JsonNode value = answer.path(field);
        if (!value.isTextual() || value.asText().isBlank()) { wrong.add("\"" + field + "\" is required and must be text"); return; }
        if (value.asText().strip().length() > max) wrong.add("\"" + field + "\" must be at most " + max + " characters");
    }

    private static String text(JsonNode answer, String field, int max) {
        String value = answer.get(field).asText().strip();
        return value.length() <= max ? value : value.substring(0, max).strip();
    }
}
