package quest.server.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.analysis.LlmClient;
import quest.server.analysis.SampleLlmClient;
import quest.server.broadcasts.BroadcastRepository;
import quest.server.broadcasts.BroadcastService;
import quest.server.chat.ChatTestSupport;
import quest.server.flags.FlagKeys;
import quest.server.teacher.AnnouncementRepository;

/**
 * Feature 29, and the owner's rule above everything else: <strong>the original text is always the record</strong>.
 * Every test here re-reads the source row afterwards, because a translation that edited what the school sent would
 * be the one bug this feature could not come back from.
 *
 * <p>The fixture is {@link ChatTestSupport}'s: school A with chat on (Sara and Noor both teach 1A, Noor also 1B)
 * and school B with chat off. What that gives this feature for free is the two cases it has to get right — Sara
 * teaches Maya but is not on the thread Maya's mother opened with Noor, and `Other` is another school's teacher
 * entirely.
 *
 * <p>The {@link LlmClient} is stubbed rather than taken from the context, as {@code VoiceDraftApiTest} does: the
 * happy path delegates to the real {@link SampleLlmClient}, whose `[ur] …` marker is deterministic, and the
 * stubbing is also how "the second request makes no model call" is a <em>proof</em> rather than an assertion about
 * a row count.
 */
class TranslationApiTest extends ChatTestSupport {
    @MockitoBean LlmClient llm;
    private final SampleLlmClient sample = new SampleLlmClient();

    @Autowired TranslationRepository translations;
    @Autowired quest.server.chat.ChatMessageRepository messages;
    @Autowired BroadcastRepository broadcasts;
    @Autowired AnnouncementRepository announcements;
    @Autowired quest.server.chat.ComplaintEventRepository complaintEvents;
    @Autowired quest.server.ai.AiCallRepository ledger;

    private String maya, omar;

    @BeforeEach void seed() throws Exception {
        seedSchools();
        maya = child("Maya", "CHSCHA", section1a);
        omar = child("Omar", "CHSCHA", section1b);
        setFlag(adminToken, A, FlagKeys.LANGUAGE_BRIDGE, true);
        setFlag(adminToken, A, FlagKeys.ANNOUNCEMENTS, true);
        setFlag(adminToken, B, FlagKeys.LANGUAGE_BRIDGE, true);          // B's own switch is on; its `chat` is not
        when(llm.name()).thenReturn("sample");
        when(llm.complete(any(), any(), any()))
                .thenAnswer(i -> sample.complete(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
    }

    @AfterEach void clean() {
        ledger.deleteAll(ledger.findAll().stream().filter(c -> c.getSchoolId() != null && c.getSchoolId().startsWith(prefix())).toList());
        complaintEvents.deleteAll(complaintEvents.findAll().stream().filter(e -> e.getSchoolId().startsWith(prefix())).toList());
        translations.deleteAll(translations.findAll().stream().filter(t -> t.getSchoolId().startsWith(prefix())).toList());
        broadcasts.deleteAll(broadcasts.findAll().stream().filter(b -> b.getSchoolId().startsWith(prefix())).toList());
        announcements.deleteAll(announcements.findAll().stream().filter(a -> a.getSchoolId().startsWith(prefix())).toList());
        removeSeed();
    }

    // ---------------------------------------------------------------- the original stays the record

    @Test void a_message_is_translated_beside_itself_and_marked_machine_translated() throws Exception {
        String messageId = saraWrites("Please send her reading book tomorrow.");

        var urdu = translated(maya, "chat", messageId, "ur");
        assertThat(urdu.get("text").asText()).isEqualTo("[ur] Please send her reading book tomorrow.");
        assertThat(urdu.get("lang").asText()).isEqualTo("ur");
        assertThat(urdu.get("machine").asBoolean()).as("every translation here is a machine's, and says so").isTrue();
        assertThat(urdu.get("original").asText()).isEqualTo("Please send her reading book tomorrow.");
        assertThat(urdu.get("sourceKind").asText()).isEqualTo("chat");
        assertThat(urdu.get("sourceId").asText()).isEqualTo(messageId);

        assertThat(messages.findById(messageId).orElseThrow().getBody())
                .as("the stored message is untouched — the owner's rule").isEqualTo("Please send her reading book tomorrow.");
    }

    @Test void a_broadcast_and_an_announcement_are_translated_without_being_rewritten() throws Exception {
        String broadcastId = broadcastTo(section1a.getId(), "Sports day", "يوم الرياضة", "Sports day is on Thursday.");
        var urdu = translated(maya, "broadcast", broadcastId, "ur");
        assertThat(urdu.get("text").asText()).isEqualTo("[ur] Sports day\n\nSports day is on Thursday.");
        assertThat(urdu.get("original").asText()).as("the title travels with the body").contains("Sports day is on Thursday.");

        var row = broadcasts.findById(broadcastId).orElseThrow();
        assertThat(row.getTitle()).isEqualTo("Sports day");
        assertThat(row.getBodyAr()).isEqualTo("يوم الرياضة");
        assertThat(row.getBodyEn()).isEqualTo("Sports day is on Thursday.");

        // Asked for Arabic, it is the English that is translated; asked for English, the Arabic.
        assertThat(translated(maya, "broadcast", broadcastId, "ar").get("original").asText()).contains("Sports day is on Thursday.");
        assertThat(translated(maya, "broadcast", broadcastId, "en").get("original").asText()).contains("يوم الرياضة");

        String noteId = announcementOn(section1a.getId(), "Tomorrow we start subtraction.");
        var note = translated(maya, "announcement", noteId, "ur");
        assertThat(note.get("text").asText()).isEqualTo("[ur] Tomorrow we start subtraction.");
        assertThat(announcements.findById(noteId).orElseThrow().getBodyEn()).isEqualTo("Tomorrow we start subtraction.");
    }

    // ---------------------------------------------------------------- the cache

    @Test void the_second_request_for_the_same_text_makes_no_model_call() throws Exception {
        String messageId = saraWrites("She did very well today.");

        var first = translated(maya, "chat", messageId, "ur");
        verify(llm, times(1)).complete(any(), any(), any());

        var second = translated(maya, "chat", messageId, "ur");
        assertThat(second.get("text").asText()).isEqualTo(first.get("text").asText());
        verify(llm, times(1)).complete(any(), any(), any());         // still one: the cache answered the second
        assertThat(translations.of("chat", messageId)).as("one row, not two").hasSize(1);
    }

    @Test void an_edited_source_is_translated_again_and_every_language_is_its_own_row() throws Exception {
        String messageId = saraWrites("She did very well today.");
        translated(maya, "chat", messageId, "ur");

        var edited = messages.findById(messageId).orElseThrow();
        edited.setBody("She did very well today, and she read aloud.");
        messages.save(edited);

        var again = translated(maya, "chat", messageId, "ur");
        assertThat(again.get("text").asText()).isEqualTo("[ur] She did very well today, and she read aloud.");
        verify(llm, times(2)).complete(any(), any(), any());

        var hindi = translated(maya, "chat", messageId, "hi");
        assertThat(hindi.get("text").asText()).isEqualTo("[hi] She did very well today, and she read aloud.");
        verify(llm, times(3)).complete(any(), any(), any());

        assertThat(translations.of("chat", messageId))
                .as("the old hash, the new one, and the new one in Hindi").hasSize(3);
    }

    // ---------------------------------------------------------------- who may translate what

    @Test void a_parent_reaches_only_her_own_familys_message() throws Exception {
        String theirs = someoneElsesChild("Theirs");
        String notHers = saraWritesTo(theirs, "A note about somebody else's child.");

        translate(maya, "chat", notHers, "ur").andExpect(status().isNotFound());
        assertThat(translations.of("chat", notHers)).isEmpty();
        verifyNoInteractions(llm);
    }

    @Test void a_parent_reaches_only_her_own_childs_feed() throws Exception {
        String otherSection = broadcastTo(section1b.getId(), "1B only", null, "This is for 1B.");
        translate(maya, "broadcast", otherSection, "ur").andExpect(status().isNotFound());
        // and the row really is in the other child's feed, so the 404 is about audience and not about the row
        translate(omar, "broadcast", otherSection, "ur").andExpect(status().isOk());

        String otherNote = announcementOn(CLASS_OTHER, "Another school's note.");
        translate(maya, "announcement", otherNote, "ur").andExpect(status().isNotFound());
    }

    /**
     * Feature 29's other reading rule, which the spec names in one breath with the threads: "the threads
     * <em>and complaints</em> they may read". A complaint is a `chat_threads` row with its own topic and its own
     * reach — {@link quest.server.chat.ComplaintService#mayRead} for staff, the child's own `child_id` for the
     * parent — so both sides have a branch of their own, and the branch also refuses a chat id sent as a complaint.
     */
    @Test void a_complaint_is_translated_only_for_the_family_and_the_staff_it_belongs_to() throws Exception {
        String mine = complaintMessage(maya, PARENT, NOOR, "الحافلة تتأخر كل صباح.");

        // Her own complaint, as a complaint: the one kind it is.
        var urdu = translated(maya, "complaint", mine, "ur");
        assertThat(urdu.get("text").asText()).isEqualTo("[ur] الحافلة تتأخر كل صباح.");
        assertThat(urdu.get("sourceKind").asText()).isEqualTo("complaint");

        // The same message named as a chat message is not a source at all: the kind is part of the check.
        translate(maya, "chat", mine, "ur").andExpect(status().isNotFound());

        // Another family's complaint, on the same section and the same recipient.
        String theirChild = someoneElsesChild("Theirs");
        String theirs = complaintMessage(theirChild, OTHER_PARENT, NOOR, "A complaint about somebody else's child.");
        translate(maya, "complaint", theirs, "ur").andExpect(status().isNotFound());

        // Staff: the recipient reads it, and a teacher of the same section who is not on it does not.
        var english = json(mvc.perform(as(post("/staff/translate"), noor).contentType(MediaType.APPLICATION_JSON)
                .content(body("complaint", mine, "en"))).andExpect(status().isOk()).andReturn());
        assertThat(english.get("sourceKind").asText()).isEqualTo("complaint");
        mvc.perform(as(post("/staff/translate"), sara).contentType(MediaType.APPLICATION_JSON)
                .content(body("complaint", mine, "en"))).andExpect(status().isNotFound());
        mvc.perform(as(post("/staff/translate"), other).contentType(MediaType.APPLICATION_JSON)
                .content(body("complaint", mine, "en"))).andExpect(status().isNotFound());

        assertThat(messages.findById(mine).orElseThrow().getBody())
                .as("the complaint's own words are untouched — the owner's rule").isEqualTo("الحافلة تتأخر كل صباح.");
    }

    @Test void staff_translate_only_a_thread_they_may_already_read() throws Exception {
        String mothersMessage = parentWritesTo(maya, NOOR, "ما فهمت الواجب، ممكن توضيح؟");

        // Noor is the thread's own staff member.
        var english = json(mvc.perform(as(post("/staff/translate"), noor).contentType(MediaType.APPLICATION_JSON)
                .content(body("chat", mothersMessage, "en"))).andExpect(status().isOk()).andReturn());
        assertThat(english.get("text").asText()).isEqualTo("[en] ما فهمت الواجب، ممكن توضيح؟");
        assertThat(english.get("machine").asBoolean()).isTrue();

        // Sara teaches Maya's section too, and that is not the same as being on this thread.
        mvc.perform(as(post("/staff/translate"), sara).contentType(MediaType.APPLICATION_JSON)
                .content(body("chat", mothersMessage, "en"))).andExpect(status().isNotFound());
        // Another school's teacher: the thread is not even visible to her.
        mvc.perform(as(post("/staff/translate"), other).contentType(MediaType.APPLICATION_JSON)
                .content(body("chat", mothersMessage, "en"))).andExpect(status().isNotFound());
        // A kind that is not a conversation: staff have both bodies of a broadcast already.
        mvc.perform(as(post("/staff/translate"), noor).contentType(MediaType.APPLICATION_JSON)
                .content(body("broadcast", mothersMessage, "en"))).andExpect(status().isBadRequest());

        assertThat(messages.findById(mothersMessage).orElseThrow().getBody()).isEqualTo("ما فهمت الواجب، ممكن توضيح؟");
    }

    // ---------------------------------------------------------------- the language, and hers

    @Test void only_the_eight_languages_are_accepted_and_hers_comes_back_on_parent_me() throws Exception {
        String messageId = saraWrites("Hello.");
        translate(maya, "chat", messageId, "de").andExpect(status().isBadRequest());

        mvc.perform(parent(put("/parent/me/reading-language")).contentType(MediaType.APPLICATION_JSON).content("{\"lang\":\"de\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(parent(put("/parent/me/reading-language")).contentType(MediaType.APPLICATION_JSON).content("{\"lang\":\"UR\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readingLanguage").value("ur"));
        assertThat(parentGet("/parent/me").get("readingLanguage").asText()).isEqualTo("ur");
    }

    // ---------------------------------------------------------------- the guards

    @Test void the_sixty_first_translation_in_an_hour_is_refused() throws Exception {
        String messageId = saraWrites("Sixty is plenty.");
        for (int i = 0; i < 60; i++) translate(maya, "chat", messageId, "ur").andExpect(status().isOk());
        translate(maya, "chat", messageId, "ur")
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("rate_limited"));
        verify(llm, times(1)).complete(any(), any(), any());
    }

    /**
     * The sixty is a ceiling on <strong>model calls per reader per hour</strong>, and the in-memory window cannot
     * enforce it: it is per instance, so two revisions of the service mean a hundred and twenty and ten mean six
     * hundred. The durable half counts the `ai_calls` rows every instance writes, under a tag that is this reader's
     * own — the rows below are what another instance would have left behind.
     */
    @Test void the_hours_model_calls_are_counted_in_the_ledger_so_the_ceiling_holds_across_instances() throws Exception {
        String first = saraWrites("One.");
        translated(maya, "chat", first, "ur");

        var mine = ledger.findByFeatureOrderByCreatedAtAsc(FlagKeys.LANGUAGE_BRIDGE).stream()
                .filter(c -> A.equals(c.getSchoolId())).toList();
        assertThat(mine).as("one call, filed under this reader").hasSize(1);
        String tag = mine.get(0).getTag();
        assertThat(tag).as("per reader, not per language").startsWith("TRANSLATE-parent:");

        for (int i = 0; i < 59; i++) {                                   // the other instance's fifty-nine
            var row = new quest.server.ai.Entities.AiCallEntity();
            row.setId("ch-ai-" + UUID.randomUUID()); row.setSchoolId(A); row.setFeature(FlagKeys.LANGUAGE_BRIDGE);
            row.setTag(tag); row.setProvider("sample"); row.setInputTokens(10); row.setOutputTokens(10);
            row.setOutcome("ok"); row.setDurationMs(5); row.setCreatedAt(Instant.now());
            ledger.save(row);
        }

        String second = saraWrites("Two.");
        translate(maya, "chat", second, "ur")
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("rate_limited"));
        assertThat(translations.of("chat", second)).as("and nothing was translated or stored").isEmpty();

        // …while the cache still answers, because a hit is not a call and costs nobody anything
        translate(maya, "chat", first, "ur").andExpect(status().isOk());
        // and another reader is untouched: the tag is hers, not the school's
        var others = ledger.findByTagOrderByCreatedAtAsc("TRANSLATE-parent:nobody");
        assertThat(others).isEmpty();
    }

    @Test void the_switch_off_hides_both_routes() throws Exception {
        String messageId = saraWrites("Hidden.");
        setFlag(adminToken, A, FlagKeys.LANGUAGE_BRIDGE, false);

        translate(maya, "chat", messageId, "ur").andExpect(status().isNotFound());
        mvc.perform(as(post("/staff/translate"), sara).contentType(MediaType.APPLICATION_JSON)
                .content(body("chat", messageId, "en"))).andExpect(status().isNotFound());
        assertThat(translations.of("chat", messageId)).isEmpty();
        verifyNoInteractions(llm);
    }

    @Test void a_school_with_chat_off_has_no_message_to_translate() throws Exception {
        String messageId = saraWrites("Quiet, please.");
        setFlag(adminToken, A, FlagKeys.CHAT, false);
        translate(maya, "chat", messageId, "ur").andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- fixtures

    /** Sara writes to Maya's mother; answers the message id. */
    private String saraWrites(String text) throws Exception { return saraWritesTo(maya, text); }

    private String saraWritesTo(String childId, String text) throws Exception {
        return json(mvc.perform(as(post("/teacher/chat/threads/" + childId + "/messages"), sara)
                .contentType(MediaType.APPLICATION_JSON).content(send(text))).andExpect(status().isCreated()).andReturn())
                .get("id").asText();
    }

    private String parentWritesTo(String childId, String staffId, String text) throws Exception {
        return parentPost("/children/" + childId + "/chat/threads/" + staffId + "/messages", send(text)).get("id").asText();
    }

    /** One complaint from one parent to one member of staff; answers the id of her own first message. */
    private String complaintMessage(String childId, String parent, String staffId, String text) throws Exception {
        var created = json(mvc.perform(post("/children/" + childId + "/complaints").header("Authorization", parent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffId\":\"" + staffId + "\",\"title\":\"The bus\",\"body\":" + ChatTestSupport.quote(text) + "}"))
                .andExpect(status().isCreated()).andReturn());
        return created.get("messages").get(0).get("id").asText();
    }

    private JsonNode translated(String childId, String kind, String sourceId, String lang) throws Exception {
        return json(translate(childId, kind, sourceId, lang).andExpect(status().isOk()).andReturn());
    }

    private org.springframework.test.web.servlet.ResultActions translate(String childId, String kind, String sourceId, String lang) throws Exception {
        return mvc.perform(parent(post("/children/" + childId + "/translate"))
                .contentType(MediaType.APPLICATION_JSON).content(body(kind, sourceId, lang)));
    }

    private static String body(String kind, String sourceId, String lang) {
        return "{\"kind\":\"" + kind + "\",\"id\":\"" + sourceId + "\",\"lang\":\"" + lang + "\"}";
    }

    private MockHttpServletRequestBuilder parent(MockHttpServletRequestBuilder b) { return b.header("Authorization", PARENT); }

    /** One broadcast to the parents of one section, written straight into the table: the composer is not what is tested. */
    private String broadcastTo(String sectionId, String title, String bodyAr, String bodyEn) {
        var row = new quest.server.broadcasts.Entities.BroadcastEntity();
        row.setId("ch-bc-" + UUID.randomUUID()); row.setSchoolId(A);
        row.setAuthorUserId(SARA); row.setAuthorRole("TEACHER"); row.setKind(BroadcastService.ANNOUNCEMENT);
        row.setTitle(title); row.setBodyEn(bodyEn); row.setBodyAr(bodyAr);
        row.setAudienceRoles(BroadcastService.PARENTS); row.setSectionIds(sectionId);
        row.setCreatedAt(Instant.now());
        return broadcasts.save(row).getId();
    }

    /** One teacher's note on a section, live now. */
    private String announcementOn(String sectionId, String bodyEn) {
        var row = new quest.server.teacher.Entities.AnnouncementEntity();
        row.setId("ch-an-" + UUID.randomUUID());
        row.setSchoolId(CLASS_OTHER.equals(sectionId) ? B : A);
        row.setTeacherId(CLASS_OTHER.equals(sectionId) ? OTHER : SARA);
        row.setClassId(sectionId); row.setBodyEn(bodyEn);
        row.setPublishedAt(Instant.now().minusSeconds(60)); row.setCreatedAt(Instant.now());
        return announcements.save(row).getId();
    }
}
