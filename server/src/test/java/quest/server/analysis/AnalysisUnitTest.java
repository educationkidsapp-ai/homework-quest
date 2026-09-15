package quest.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import quest.api.CacheKeys;
import quest.api.dto.Play;
import quest.api.samples.HotSoupSeed;
import quest.api.validation.SchemaValidator;
import quest.server.admin.AdminPipelineTest;
import quest.server.config.ApiException;

class AnalysisUnitTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void stop_ids_become_lesson_unique_including_exit_questions_and_page_images() throws Exception {
        String lessonId = "abcdef12-3456";
        Play play = HotSoupSeed.INSTANCE.getLesson().getPlays().get(0);
        ObjectNode node = (ObjectNode) mapper.readTree(SchemaValidator.INSTANCE.getJson().encodeToString(Play.Companion.serializer(), play));
        ((ObjectNode) node.get("stops").get(2)).put("pageImageId", "page-1");
        StopIds.relabel(node, lessonId, 1, 0);
        assertThat(node.get("id").asText()).isEqualTo(lessonId + ":1:0");
        for (var s : node.get("stops")) assertThat(s.get("id").asText()).startsWith("abcdef12:1:0:");
        var exit = node.get("stops").get(node.get("stops").size() - 1);
        for (var q : exit.get("questions")) assertThat(q.get("id").asText()).startsWith("abcdef12:1:0:");
        assertThat(node.get("stops").get(2).get("pageImageId").asText()).isEqualTo("abcdef12:page-1");
        // still a valid play after relabelling
        assertThat(SchemaValidator.INSTANCE.validatePlayJson(node.toString(), 1, java.util.Set.of()).getErrors()).isEmpty();
        // idempotent
        StopIds.relabel(node, lessonId, 1, 0);
        assertThat(node.get("stops").get(0).get("id").asText()).isEqualTo("abcdef12:1:0:hs1-move");
    }

    @Test void code_fences_are_stripped() {
        assertThat(DeepSeekClient.stripFences("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(DeepSeekClient.stripFences("  {\"a\":1} ")).isEqualTo("{\"a\":1}");
        assertThat(DeepSeekClient.stripFences("{\"a\":null,\"b\":[{\"c\":null,\"d\":1}]}")).isEqualTo("{\"b\":[{\"d\":1}]}");
    }

    @Test void cache_keys_follow_the_shared_rule() {
        String h = CacheKeys.INSTANCE.sourceHash(List.of("b", "a"), quest.api.dto.Curriculum.BRITISH, 1, quest.api.dto.Subject.ENGLISH, null);
        assertThat(h).isEqualTo(CacheKeys.INSTANCE.sourceHash(List.of("a", "b"), quest.api.dto.Curriculum.BRITISH, 1, quest.api.dto.Subject.ENGLISH, "  "));
        assertThat(h).isNotEqualTo(CacheKeys.INSTANCE.sourceHash(List.of("a", "b"), quest.api.dto.Curriculum.AMERICAN, 1, quest.api.dto.Subject.ENGLISH, null));
        assertThat(CacheKeys.INSTANCE.playKey(h, 1, 1, 0)).endsWith("|B|1|1|0|" + CacheKeys.PROMPT_B_VERSION);
        assertThat(StopIds.resolve("L2:s6", "abcdef12-x", java.util.Map.of(2, java.util.Set.of("s6")))).isEqualTo("abcdef12:2:0:s6");
        assertThat(StopIds.resolve("level1_s6", "abcdef12-x", java.util.Map.of(1, java.util.Set.of("s6")))).isEqualTo("abcdef12:1:0:s6");
        assertThat(StopIds.resolve("s9", "abcdef12-x", java.util.Map.of(3, java.util.Set.of("s9")))).isEqualTo("abcdef12:3:0:s9");
        assertThat(StopIds.resolve("nope", "abcdef12-x", java.util.Map.of(1, java.util.Set.of("s6")))).isNull();
    }

    @Test void slides_are_split_into_pages_with_text_and_a_render() throws Exception {
        var processor = new SlideProcessor();
        var source = processor.process("slides.pdf", "application/pdf", AdminPipelineTest.pdf("Page one text", "Page two text"));
        assertThat(source.pages()).hasSize(2);
        assertThat(source.pages().get(0).text()).contains("Page one text");
        assertThat(source.pages().get(1).png()).isNotEmpty();
        assertThat(source.pages().get(1).width()).isLessThanOrEqualTo(SlideProcessor.RENDER_PX);
        assertThat(source.textDump()).contains("--- Page 2 ---");
        assertThatThrownBy(() -> processor.process("notes.txt", "text/plain", "hello".getBytes())).isInstanceOf(ApiException.class).hasMessageContaining("unreadable_file");
        assertThatThrownBy(() -> processor.process("broken.pdf", "application/pdf", "nope".getBytes())).isInstanceOf(ApiException.class).hasMessageContaining("unreadable_file");
    }

    @Test void sample_client_answers_every_prompt_with_schema_valid_json() {
        var llm = new SampleLlmClient();
        var a = llm.complete(Prompts.SYSTEM_A, Prompts.userA("british", 1, "english", "Hot Soup", "text", false), List.of());
        assertThat(SchemaValidator.INSTANCE.validateAnalysisJson(a.text()).getErrors()).isEmpty();
        var b = llm.complete(Prompts.SYSTEM_B, Prompts.userB(2, 0, a.text(), "[]", null, 7, List.of()), List.of());
        assertThat(SchemaValidator.INSTANCE.validatePlayJson(b.text(), 2, java.util.Set.of()).getErrors()).isEmpty();
        var c = llm.complete(Prompts.SYSTEM_C, Prompts.userC(a.text(), "[]"), List.of());
        assertThat(SchemaValidator.INSTANCE.validatePanelJson(c.text()).getErrors()).isEmpty();
    }

    @Test void model_output_is_repaired_before_validation() throws Exception {
        String raw = "{\"stops\":[{\"type\":\"trueFalse\",\"id\":\"q1\",\"speak\":\"" + "a ".repeat(60) + "end\",\"statement\":\"x\",\"answer\":true},"
                + "{\"type\":\"storyPieces\",\"cards\":[{\"piece\":\"Title\"},{\"piece\":\"genre\"},{\"piece\":\"characters\"},{\"piece\":\"setting\"},{\"piece\":\"plot\"},{\"piece\":\"problem\"},{\"piece\":\"resolution\"}]},"
                + "{\"type\":\"retell\",\"cues\":[{\"stage\":\"Beginning\",\"cue\":\"x\",\"illustrationKey\":\"grandpa\"}]}]}";
        var node = mapper.readTree(LlmJson.cleanIllustrations(raw));
        var tf = node.get("stops").get(0);
        assertThat(tf.get("hint").asText()).isNotBlank();
        assertThat(tf.get("speak").asText().length()).isLessThanOrEqualTo(90);
        assertThat(tf.get("speak").asText()).endsWith("…");
        var cards = node.get("stops").get(1).get("cards");
        assertThat(cards).hasSize(6);
        assertThat(cards.get(0).get("piece").asText()).isEqualTo("title");
        var cue = node.get("stops").get(2).get("cues").get(0);
        assertThat(cue.get("stage").asText()).isEqualTo("beginning");
        assertThat(cue.has("illustrationKey")).isFalse();
    }

    /** The QA failure of 2026-09-15: a wordCards entry without illustrationKey (+ one plain-string entry). */
    @Test void word_cards_without_a_picture_key_are_repaired_and_errors_name_only_the_real_type() throws Exception {
        String raw = "{\"stops\":[{\"type\":\"wordCards\",\"id\":\"s2\",\"title\":\"New words\",\"speak\":\"Tap a word.\",\"ingredient\":{\"emoji\":\"🥕\",\"name\":\"carrot\"},\"parentTip\":{\"en\":\"x\",\"ar\":\"y\"},"
                + "\"words\":[{\"word\":\"fish\",\"meaning\":\"m\",\"sentence\":\"s\",\"illustrationKey\":\"fish\"},{\"word\":\"ship\",\"meaning\":\"m\",\"sentence\":\"s\"},{\"word\":\"whisper\",\"meaning\":\"m\",\"sentence\":\"s\"},\"stray\"]}]}";
        var words = mapper.readTree(LlmJson.cleanIllustrations(raw)).get("stops").get(0).get("words");
        assertThat(words).hasSize(3);
        assertThat(words.get(1).get("illustrationKey").asText()).isEqualTo("ship");   // drawable → the word itself
        assertThat(words.get(2).get("illustrationKey").asText()).isEqualTo("book");   // not drawable → neutral card
        // and when something is still wrong, the message names only the declared type's problem
        var errors = SchemaValidator.INSTANCE.validatePlayJson("{\"level\":2,\"variant\":0,\"kind\":\"story\",\"theme\":{\"potName\":\"p\",\"dishName\":\"d\",\"potEmoji\":\"🍲\",\"servedText\":\"s\"},\"stops\":[" + raw.substring(raw.indexOf('[') + 1, raw.lastIndexOf(']')) + "]}", 2, java.util.Set.of()).getErrors();
        assertThat(errors).isNotEmpty();
        assertThat(errors).allSatisfy(e -> assertThat(e).doesNotContain("does not match constant").doesNotContain("sentences").doesNotContain("cards"));
        assertThat(String.join(" ", errors)).contains("illustrationKey");
    }
}
