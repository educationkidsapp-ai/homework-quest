package quest.server.translate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import quest.api.dto.ReadingLanguage;
import quest.api.dto.TranslateRequest;
import quest.api.dto.Translation;
import quest.api.dto.TranslationSource;
import quest.server.ai.AiFailure;
import quest.server.ai.AiInputGuard;
import quest.server.ai.AiService;
import quest.server.ai.AiTask;
import quest.server.ai.AiVerdict;
import quest.server.analysis.Prompts;
import quest.server.auth.Principals;
import quest.server.broadcasts.BroadcastService;
import quest.server.chat.ChatMessageRepository;
import quest.server.chat.ChatService;
import quest.server.chat.ChatThreadRepository;
import quest.server.chat.ComplaintService;
import quest.server.chat.Entities.ChatMessageEntity;
import quest.server.chat.Entities.ChatThreadEntity;
import quest.server.children.ChildService;
import quest.server.children.Entities.ChildEntity;
import quest.server.config.ApiException;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.teacher.AnnouncementService;
import quest.server.translate.Entities.TranslationEntity;

/**
 * Feature 29: <strong>the original text is always the record; a translation is only ever an addition.</strong> That
 * is the owner's rule, and everything here follows from it — no source row is written to, the answer carries the
 * original back with it, and {@code machine} is true on every answer because every answer here is a machine's.
 *
 * <p><strong>This class adds no reading rule of its own.</strong> Whether a source may be translated is exactly
 * whether the caller may already read it, asked of the code that already decides:
 * {@link ChildService#owned} and the thread's own {@code childId} for a parent, {@link ChatService#mayReadThread}
 * and {@link ComplaintService#mayRead} for a member of staff, {@link BroadcastService#readableByParent} and
 * {@link AnnouncementService#readableByParent} for a feed. Anything else is a 404 and never a 403: whether another
 * family's message exists is not this caller's to learn. The feature's own switch is on the controller; the switch
 * that lets her read the <em>original</em> (`chat`, `announcements`) is checked here, because a school with chat off
 * has no message to translate.
 *
 * <p><strong>The cache is the feature's economics.</strong> The key is the source, the language and the SHA-256 of
 * the exact text that was sent — so a hit makes no model call at all, an <em>edited</em> source hashes differently
 * and is translated again, and each language is its own row. The hash is taken <em>after</em>
 * {@link AiInputGuard}'s masking, which is the right place for the one case it decides: a parent who corrects only
 * her phone number has not changed anything the model would see, and is served from the cache.
 *
 * <p>E1's phases, as {@link quest.server.broadcasts.VoiceDraftService} has them: read what the prompt needs, let the
 * transaction go, call the model, then take one short transaction to store the answer. A model call is seconds and a
 * connection held across one is ten readers being the whole pool.
 */
@Service
public class TranslationService {
    /** A chat body is ≤ 2,000 characters and a broadcast ≤ 1,000 plus a title; nothing the bridge translates is longer. */
    static final int MAX_SOURCE = 4000;
    /** `{ "code": "rephrase" }` — the model could not produce a usable translation, twice. */
    public static final String REPHRASE = "rephrase";
    /** `translations.source_kind`, which is {@link TranslationSource}'s own four words. */
    static final String CHAT = "chat", BROADCAST = "broadcast", ANNOUNCEMENT = "announcement", COMPLAINT = "complaint";

    /**
     * <strong>E-mail addresses and telephone numbers, and nothing else.</strong> Names stay: "ask Nada's mother to
     * send the book" is the content, and a parent handed "[name]" cannot act on the sentence she was sent. A number
     * and an address are different — they identify a person outside the school's own records and they are never the
     * point of a message, so they are masked and the model translates around the placeholders.
     */
    private static final AiInputGuard GUARD = AiInputGuard.numbersOnly();

    /** `http://…`, `https://…` or `www.…`: what a reader could be led to tap. */
    private static final Pattern LINK = Pattern.compile("(?:https?://|www\\.)[^\\s<>\"')\\]]+", Pattern.CASE_INSENSITIVE);

    private final TranslationRepository cache; private final TranslationRateLimiter limiter;
    private final AiService ai; private final FeatureFlags flags; private final Clock clock; private final TransactionTemplate tx;
    private final ChildService children; private final ChatMessageRepository messages; private final ChatThreadRepository threads;
    private final ChatService chat; private final ComplaintService complaints;
    private final BroadcastService broadcasts; private final AnnouncementService announcements;

    public TranslationService(TranslationRepository cache, TranslationRateLimiter limiter, AiService ai, FeatureFlags flags,
                              Clock clock, PlatformTransactionManager transactions, ChildService children,
                              ChatMessageRepository messages, ChatThreadRepository threads, ChatService chat,
                              ComplaintService complaints, BroadcastService broadcasts, AnnouncementService announcements) {
        this.cache = cache; this.limiter = limiter; this.ai = ai; this.flags = flags; this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.children = children; this.messages = messages; this.threads = threads; this.chat = chat;
        this.complaints = complaints; this.broadcasts = broadcasts; this.announcements = announcements;
    }

    // ---------------------------------------------------------------- the parent (app)

    /**
     * `POST /children/{id}/translate`. The child is hers or this is a 404 ({@link ChildService#owned}), and then the
     * source is one her own thread or this child's own feed shows her — four kinds, four checks, each the one the
     * route she reads it on already applies.
     */
    public Translation forParent(Principals.Parent parent, String childId, TranslateRequest request) {
        var child = children.owned(childId, parent);
        String lang = language(request);
        var source = parentSource(child, request, lang);
        return run(source, lang, "parent:" + parent.parentId());
    }

    private Source parentSource(ChildEntity child, TranslateRequest request, String lang) {
        String id = sourceId(request);
        return switch (request.getKind()) {
            case CHAT -> ownMessage(child, id, false);
            case COMPLAINT -> ownMessage(child, id, true);
            case BROADCAST -> {
                requireOn(child.getSchoolId(), FlagKeys.ANNOUNCEMENTS);
                var row = broadcasts.readableByParent(child, id);
                yield new Source(BROADCAST, row.getId(), row.getSchoolId(), feedText(row.getTitle(), row.getBodyAr(), row.getBodyEn(), lang));
            }
            case ANNOUNCEMENT -> {
                requireOn(child.getSchoolId(), FlagKeys.ANNOUNCEMENTS);
                var row = announcements.readableByParent(child, id, clock.instant());
                yield new Source(ANNOUNCEMENT, row.getId(), row.getSchoolId(), feedText(null, row.getBodyAr(), row.getBodyEn(), lang));
            }
        };
    }

    /**
     * One message of a conversation about <strong>this</strong> child: the thread's own `child_id` is the isolation,
     * and it is also the whole of the parent's rule, because a thread about her child is a thread she is on. A
     * parent carries no tenant scope, so the message is looked up unfiltered and pinned to the school by that
     * column — the shape every parent-facing read of a tenant table has.
     */
    private Source ownMessage(ChildEntity child, String messageId, boolean complaint) {
        requireOn(child.getSchoolId(), FlagKeys.CHAT);
        var message = messages.findOneById(messageId).orElseThrow(TranslationService::noSource);
        var thread = threads.findOneById(message.getThreadId()).orElseThrow(TranslationService::noSource);
        if (!child.getId().equals(thread.getChildId()) || complaint != isComplaint(thread)) throw noSource();
        return body(message, complaint);
    }

    // ---------------------------------------------------------------- the staff (dashboard and the staff app)

    /**
     * `POST /staff/translate`: one message of a thread or a complaint this caller may already read, decided by her
     * own role's checks. A broadcast is the school's own words, written in Arabic and English by the school itself,
     * so there is nothing here to translate for her and the two feed kinds are a 400 rather than a 404 — it is a
     * request that could never be right, not a row she may not see.
     */
    public Translation forStaff(Principals.User caller, TranslateRequest request) {
        String lang = language(request);
        var message = messages.findOneById(sourceId(request)).orElseThrow(TranslationService::noSource);
        var thread = threads.findOneById(message.getThreadId()).orElseThrow(TranslationService::noSource);
        boolean mayRead = switch (request.getKind()) {
            case CHAT -> chat.mayReadThread(thread, caller);
            case COMPLAINT -> complaints.mayRead(thread, caller);
            case BROADCAST, ANNOUNCEMENT -> throw ApiException.badRequest("Staff translate a chat or a complaint message; a broadcast is already written in Arabic and English.");
        };
        if (!mayRead) throw noSource();
        return run(body(message, isComplaint(thread)), lang, "user:" + caller.userId());
    }

    // ---------------------------------------------------------------- the cache, then the model

    /**
     * The one path both sides take. The rate guard comes first and counts every request, a cache hit included — the
     * cache stops the bill but not the traffic, and what is worth refusing is a loop ({@link TranslationRateLimiter}).
     */
    private Translation run(Source source, String lang, String readerKey) {
        limiter.record(readerKey);
        String sent = GUARD.mask(source.text());
        String hash = sha256(sent);
        var hit = cache.hit(source.schoolId(), source.kind(), source.id(), lang, hash);
        String text = hit.map(TranslationEntity::getText).orElseGet(() -> store(source, lang, hash, ask(source, lang, sent)));
        return new Translation(text, ReadingLanguage.valueOf(lang.toUpperCase(java.util.Locale.ROOT)), true,
                source.text(), kindOf(source.kind()), source.id());
    }

    /** Two attempts with the validator's own words carried into the second, exactly as every other AI feature asks. */
    private String ask(Source source, String lang, String sent) {
        try {
            return ai.run(AiTask.of("languageBridge", "TRANSLATE-" + lang, source.schoolId(), Prompts.TRANSLATE,
                            Prompts.userTranslate(ReadingLanguages.label(lang), sent))
                    // The user turn is already masked, so this masks nothing twice; it is on the task because that is
                    // where the ledger and any future reader of it look to see what the provider was allowed to see.
                    .guardedBy(GUARD)
                    .retrying(AiTask.Retry.saying("Your previous answer was rejected:", "Answer again with the translation only."))
                    .reading((raw, attempt) -> {
                        String answer = raw == null ? "" : raw.strip();
                        var wrong = problems(answer, sent);
                        return wrong.isEmpty() ? AiVerdict.ok(answer) : AiVerdict.rejected(wrong);
                    })
                    .keepingRejects()).text();
        } catch (AiFailure f) {
            if (f.kind() == AiFailure.Kind.INVALID)
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, REPHRASE,
                        "Couldn't translate that message. " + f.errorsJoined(3));
            if (f.isTransient()) throw new ApiException(HttpStatus.BAD_GATEWAY, quest.api.dto.ApiError.MODEL_FAILED,
                    "The AI is busy — try that again in a moment.");
            throw new ApiException(HttpStatus.BAD_GATEWAY, quest.api.dto.ApiError.MODEL_FAILED, f.getMessage());
        }
    }

    /**
     * What a usable translation is, in three checks and no more. Judging the <em>quality</em> of a translation is not
     * something the server can do — that is the leftover the spec names — so it refuses only what is plainly not one:
     *
     * <ul>
     *   <li>nothing at all;</li>
     *   <li>more than three times the source plus 200 characters, which is a model that started explaining itself
     *       (three times, because a Latin script can genuinely run long against Arabic);</li>
     *   <li>a link the source did not have. This is the one that matters: a translation is text a parent is shown
     *       as her school's own, and a model that invents a URL has invented somewhere for her to be sent.</li>
     * </ul>
     */
    private static List<String> problems(String answer, String sent) {
        var wrong = new ArrayList<String>();
        if (answer.isEmpty()) wrong.add("the answer was empty");
        if (answer.length() > 3 * sent.length() + 200) wrong.add("the answer is far longer than the message; translate it, do not explain it");
        var links = LINK.matcher(answer);
        while (links.find()) {
            String link = links.group();
            if (!sent.toLowerCase(java.util.Locale.ROOT).contains(link.toLowerCase(java.util.Locale.ROOT)))
                wrong.add("you added a link that is not in the message: " + link);
        }
        return wrong;
    }

    /**
     * The answer, cached. One short transaction of its own, after the model call rather than around it, and a race
     * is not an error: the unique index means the other request stored a translation of the same text into the same
     * language, so this one simply keeps the text it was given.
     */
    private String store(Source source, String lang, String hash, String text) {
        var row = new TranslationEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(source.schoolId());
        row.setSourceKind(source.kind()); row.setSourceId(source.id()); row.setSourceHash(hash);
        row.setLang(lang); row.setText(text); row.setCreatedAt(clock.instant());
        try { tx.executeWithoutResult(t -> cache.saveAndFlush(row)); }
        catch (DataIntegrityViolationException raced) { /* another reader asked for the very same thing first */ }
        return text;
    }

    // ---------------------------------------------------------------- what is translated

    /** The source text and where it came from. {@code text} is both what is sent and what comes back as `original`. */
    private record Source(String kind, String id, String schoolId, String text) {}

    private Source body(ChatMessageEntity message, boolean complaint) {
        return new Source(complaint ? COMPLAINT : CHAT, message.getId(), message.getSchoolId(), require(message.getBody()));
    }

    /**
     * A feed row's own text: the title, when it has one, and <strong>one</strong> of the two bodies. The school
     * writes a broadcast twice over, so the version to translate is <strong>the one that is not already the target
     * language</strong> — asked for Arabic it is the English that is translated, asked for English it is the Arabic,
     * and for the other six it is the English, because more of the school's own wording is written and checked
     * there. Whichever is missing, the other one is used: `broadcasts.body_en` is NOT NULL and `body_ar` is not.
     */
    private String feedText(String title, String bodyAr, String bodyEn, String lang) {
        String ar = bodyAr == null || bodyAr.isBlank() ? null : bodyAr.strip();
        String en = bodyEn == null || bodyEn.isBlank() ? null : bodyEn.strip();
        String body = switch (lang) {
            case "ar" -> en != null ? en : ar;
            case "en" -> ar != null ? ar : en;
            default -> en != null ? en : ar;
        };
        return require(title == null || title.isBlank() || body == null ? body : title.strip() + "\n\n" + body);
    }

    private String language(TranslateRequest request) {
        return ReadingLanguages.require(request.getLang().name());
    }

    private static String sourceId(TranslateRequest request) {
        String id = request.getId() == null ? "" : request.getId().strip();
        if (id.isEmpty()) throw ApiException.badRequest("Send the id of the message, broadcast or announcement to translate.");
        return id;
    }

    private static String require(String text) {
        String out = text == null ? "" : text.strip();
        if (out.isEmpty()) throw ApiException.badRequest("There is nothing to translate in that one.");
        if (out.length() > MAX_SOURCE) throw ApiException.badRequest("That is longer than the bridge translates (" + MAX_SOURCE + " characters).");
        return out;
    }

    private static boolean isComplaint(ChatThreadEntity thread) { return ChatService.COMPLAINT.equals(thread.getTopic()); }

    private static TranslationSource kindOf(String kind) {
        return switch (kind) {
            case BROADCAST -> TranslationSource.BROADCAST;
            case ANNOUNCEMENT -> TranslationSource.ANNOUNCEMENT;
            case COMPLAINT -> TranslationSource.COMPLAINT;
            default -> TranslationSource.CHAT;
        };
    }

    /** The same body an unknown route gets, for every reason a source is not this caller's to translate. */
    private static ApiException noSource() { return new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such message."); }

    /** A school with the feature behind the original switched off has no original to translate. */
    private void requireOn(String schoolId, String flag) {
        if (!flags.isOn(schoolId, flag)) throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such endpoint.");
    }

    /** Hex SHA-256 of the text as it was sent; `translations.source_hash`. */
    static String sha256(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            var out = new StringBuilder(64);
            for (byte b : digest) out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
