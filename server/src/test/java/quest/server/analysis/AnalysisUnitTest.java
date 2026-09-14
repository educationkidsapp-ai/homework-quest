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
}
