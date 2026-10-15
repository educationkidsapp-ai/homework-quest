package quest.server.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import quest.api.dto.ComplaintCategory;
import quest.api.dto.ComplaintTriage;
import quest.api.dto.ComplaintUrgency;
import quest.server.ai.AiFailure;
import quest.server.ai.AiInputGuard;
import quest.server.ai.AiService;
import quest.server.ai.AiTask;
import quest.server.ai.AiVerdict;
import quest.server.analysis.Prompts;
import quest.server.auth.ParentRepository;
import quest.server.auth.UserRepository;
import quest.server.chat.Entities.ChatThreadEntity;
import quest.server.chat.Entities.ComplaintTriageEntity;
import quest.server.children.ChildRepository;
import quest.server.config.Json;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.notifications.NotificationService;
import quest.server.tenancy.ClassRepository;

/**
 * Feature 27: <strong>a new complaint is sorted for the member of staff who will answer it</strong> — a category, an
 * urgency, a short summary in Arabic and English, and a reply she edits and sends herself.
 *
 * <p><strong>It cannot touch the complaint.</strong> {@link ComplaintService#create} publishes
 * {@link Requested} and returns; the triage runs {@code AFTER_COMMIT} and on a task thread, which is
 * {@code OutgoingMail}'s and {@link quest.server.push.ParentPush}'s pattern and for the same three reasons, sharpened
 * here by a model call being seconds rather than milliseconds:
 *
 * <ul>
 *   <li>the parent's {@code POST …/complaints} answers in the time it always did, whatever the provider is doing;</li>
 *   <li>no database transaction and no Hikari connection is held across a model call (E1's lesson);</li>
 *   <li>a complaint that rolled back is never triaged, and a triage that fails — a busy provider, a nonsense answer
 *       twice, a bell that cannot be written — is logged and dropped. <em>There is no path from here back into the
 *       complaint.</em></li>
 * </ul>
 *
 * <p><strong>It sends nothing.</strong> No {@link ChatService#send}, no socket frame, no message row: the suggested
 * reply is a string on a row, "Use suggested reply" fills the box on the dashboard, and Send is still the member of
 * staff pressing the existing {@code POST /{area}/complaints/{id}/messages}. The one thing it does tell anybody is
 * the department manager, once, when the urgency is high.
 *
 * <p><strong>What the provider is told</strong> is the complaint's title and its first message and nothing else,
 * with the child's, the parent's and the recipient's names, every e-mail address and every telephone number masked
 * first ({@link AiInputGuard}). Nothing the school's own tables hold about the family travels.
 *
 * <p><strong>No caller, so no tenant scope.</strong> Like {@link OfficeHoursSummaryJob} and the lesson pipeline, this
 * runs on a thread {@link quest.server.tenancy.TenantContext} was never set on, so its reads are unfiltered by
 * construction and the school is the complaint's own — read off the row, never passed in. The row it writes carries
 * that same {@code school_id}, which is what every filtered read of it afterwards is scoped by.
 */
@Component
public class ComplaintTriageService {
    /** The table's own ceilings (V49), asserted on the answer rather than cut into it. */
    static final int SUMMARY_MAX = 400, REPLY_MAX = 600;
    /** `category` as the migration's CHECK constraint and {@link ComplaintCategory} both spell it. */
    static final Set<String> CATEGORIES = Set.of("academic", "behaviour", "transport", "fees", "facilities", "staff", "other");
    static final Set<String> URGENCIES = Set.of("low", "normal", "high");
    static final String HIGH = "high";

    private static final Logger log = LoggerFactory.getLogger(ComplaintTriageService.class);

    /** One complaint to read, named by its id alone: everything the model needs is read on the task thread. */
    public record Requested(String complaintId) {}

    private final ApplicationEventPublisher events; private final ChatThreadRepository threads;
    private final ChatMessageRepository messages; private final ComplaintTriageRepository triages;
    private final ChildRepository children; private final ClassRepository classes; private final UserRepository users;
    private final ParentRepository parents; private final ChatPeers peers; private final NotificationService bells;
    private final FeatureFlags flags; private final AiService ai; private final Json json; private final Clock clock;
    private final TransactionTemplate tx;

    public ComplaintTriageService(ApplicationEventPublisher events, ChatThreadRepository threads, ChatMessageRepository messages,
                                  ComplaintTriageRepository triages, ChildRepository children, ClassRepository classes,
                                  UserRepository users, ParentRepository parents, ChatPeers peers, NotificationService bells,
                                  FeatureFlags flags, AiService ai, Json json, Clock clock, PlatformTransactionManager transactions) {
        this.events = events; this.threads = threads; this.messages = messages; this.triages = triages;
        this.children = children; this.classes = classes; this.users = users; this.parents = parents; this.peers = peers;
        this.bells = bells; this.flags = flags; this.ai = ai; this.json = json; this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Called by {@link ComplaintService#create} inside its own transaction; nothing happens until that commits. */
    public void request(String complaintId) { if (complaintId != null) events.publishEvent(new Requested(complaintId)); }

    /**
     * The whole feature, on a task thread. Every failure ends here: the complaint has already been created, answered
     * and pushed, and a school with no triage row is the ordinary case rather than an error.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequested(Requested event) {
        try { triage(event.complaintId()); }
        catch (AiFailure f) { log.warn("complaint triage: {} was not triaged ({}): {}", event.complaintId(), f.kind(), f.getMessage()); }
        catch (RuntimeException e) { log.warn("complaint triage: {} was not triaged: {}", event.complaintId(), e.toString()); }
    }

    /** Package-private so a test can run it on the calling thread; `onRequested` is the only caller in production. */
    void triage(String complaintId) {
        var complaint = threads.findById(complaintId).filter(t -> ChatService.COMPLAINT.equals(t.getTopic())).orElse(null);
        if (complaint == null) return;
        String schoolId = complaint.getSchoolId();
        // The switch, before the model: a school with it off makes no AI call at all and so is billed for none.
        if (!flags.isOn(schoolId, FlagKeys.COMPLAINT_TRIAGE)) return;
        if (triages.existsById(complaintId)) return;                            // triaged once; the id is the primary key

        String title = complaint.getTitle() == null ? "" : complaint.getTitle();
        String body = messages.oldest(complaintId, PageRequest.of(0, 1)).stream()
                .map(Entities.ChatMessageEntity::getBody).findFirst().orElse("");
        var accepted = ai.run(AiTask.of("complaintTriage", "F-" + schoolId, schoolId, Prompts.COMPLAINT_TRIAGE, Prompts.userComplaintTriage(title, body))
                .guardedBy(AiInputGuard.masking(namesAround(complaint)))
                .reading(this::read)
                .keepingRejects()).json();

        var row = new ComplaintTriageEntity();
        row.setComplaintId(complaintId); row.setSchoolId(schoolId);
        row.setCategory(word(accepted, "category")); row.setUrgency(word(accepted, "urgency"));
        row.setSummaryAr(text(accepted, "summaryAr")); row.setSummaryEn(text(accepted, "summaryEn"));
        row.setReplyAr(text(accepted, "replyAr")); row.setReplyEn(text(accepted, "replyEn"));
        row.setCreatedAt(clock.instant());
        tx.executeWithoutResult(status -> triages.save(row));
        if (HIGH.equals(row.getUrgency())) escalate(complaint, title);
    }

    // ---------------------------------------------------------------- reading the answer

    /**
     * The shape, judged whole so that the second attempt is told everything that was wrong with the first. An
     * over-long summary or reply is <em>rejected</em> rather than cut: half a suggested reply reads as a decision the
     * school has made, and the model is perfectly able to write a shorter one when asked.
     */
    private AiVerdict read(String raw, int attempt) {
        JsonNode candidate;
        try { candidate = json.tree(raw); }
        catch (RuntimeException e) { return AiVerdict.rejected("the answer was not JSON at all"); }
        if (!candidate.isObject()) return AiVerdict.rejected("the answer was not a JSON object");
        var wrong = new ArrayList<String>();
        String category = word(candidate, "category");
        if (!CATEGORIES.contains(category)) wrong.add("\"category\" must be one of " + String.join(", ", CATEGORIES.stream().sorted().toList()));
        String urgency = word(candidate, "urgency");
        if (!URGENCIES.contains(urgency)) wrong.add("\"urgency\" must be low, normal or high");
        for (String field : List.of("summaryAr", "summaryEn")) check(candidate, field, SUMMARY_MAX, wrong);
        for (String field : List.of("replyAr", "replyEn")) check(candidate, field, REPLY_MAX, wrong);
        return wrong.isEmpty() ? AiVerdict.ok(candidate) : AiVerdict.rejected(wrong);
    }

    private static void check(JsonNode answer, String field, int max, List<String> wrong) {
        String value = text(answer, field);
        if (value.isEmpty()) wrong.add("\"" + field + "\" is required");
        else if (value.length() > max) wrong.add("\"" + field + "\" must be at most " + max + " characters (yours was " + value.length() + ")");
    }

    private static String word(JsonNode answer, String field) { return text(answer, field).toLowerCase(Locale.ROOT); }

    private static String text(JsonNode answer, String field) {
        var value = answer.path(field);
        return value.isTextual() ? value.asText().strip() : "";
    }

    // ---------------------------------------------------------------- the one escalation

    /**
     * High urgency reaches the manager(s) of the child's department as well, on B6's own `complaint.new` bell and
     * linked to her own Complaints page. The recipient is skipped when she is one of them: she has already been rung
     * for this complaint, and being told twice about the same thing is noise, not urgency.
     */
    private void escalate(ChatThreadEntity complaint, String title) {
        var section = section(complaint);
        if (section == null) return;
        for (var manager : peers.managersOn(complaint.getSchoolId(), section))
            if (!manager.getId().equals(complaint.getTeacherId()))
                bells.complaintEscalated(complaint.getSchoolId(), manager.getId(), complaint.getId(), title);
    }

    /** The child's section, which is what names her department; null for a child who has been taken off the roster. */
    private quest.server.tenancy.Entities.ClassEntity section(ChatThreadEntity complaint) {
        if (complaint.getChildId() == null) return null;
        var kid = children.findById(complaint.getChildId()).orElse(null);
        if (kid == null || kid.getClassId() == null) return null;
        return classes.findById(kid.getClassId()).orElse(null);
    }

    // ---------------------------------------------------------------- what the provider is not told

    /**
     * The names {@link AiInputGuard} masks: the child's, her parent's and the recipient's. The parent's own words can
     * of course still describe somebody without naming her — that is the limit feature 22 has too — but no name the
     * school's own tables hold travels to the provider.
     */
    private List<String> namesAround(ChatThreadEntity complaint) {
        var names = new ArrayList<String>();
        var kid = complaint.getChildId() == null ? null : children.findById(complaint.getChildId()).orElse(null);
        if (kid != null) {
            names.add(kid.getName());
            if (kid.getParentId() != null) parents.findById(kid.getParentId()).ifPresent(p -> names.add(ChatService.name(p)));
        }
        if (complaint.getTeacherId() != null) users.findById(complaint.getTeacherId()).ifPresent(u -> names.add(ChatService.name(u)));
        return names;
    }

    // ---------------------------------------------------------------- what the staff read

    /** The row as the staff see it, or null for "not triaged" — the switch off, the model failed, or it is still running. */
    static ComplaintTriage view(ComplaintTriageEntity row) {
        if (row == null) return null;
        return new ComplaintTriage(category(row.getCategory()), urgency(row.getUrgency()),
                row.getSummaryAr(), row.getSummaryEn(), row.getReplyAr(), row.getReplyEn());
    }

    /** A value V49's CHECK constraint already refused is impossible here; `other` and `normal` are the safe readings. */
    private static ComplaintCategory category(String stored) {
        return switch (stored == null ? "" : stored) {
            case "academic" -> ComplaintCategory.ACADEMIC; case "behaviour" -> ComplaintCategory.BEHAVIOUR;
            case "transport" -> ComplaintCategory.TRANSPORT; case "fees" -> ComplaintCategory.FEES;
            case "facilities" -> ComplaintCategory.FACILITIES; case "staff" -> ComplaintCategory.STAFF;
            default -> ComplaintCategory.OTHER;
        };
    }

    private static ComplaintUrgency urgency(String stored) {
        return switch (stored == null ? "" : stored) {
            case "low" -> ComplaintUrgency.LOW; case HIGH -> ComplaintUrgency.HIGH; default -> ComplaintUrgency.NORMAL;
        };
    }
}
