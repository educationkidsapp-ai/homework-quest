package quest.server.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import quest.server.ApiTestSupport;

/**
 * `server/openapi.json` is the source the Angular client is generated from, so it is committed and checked for drift.
 * Run `./mvnw test -Dtest=OpenApiExportTest -Dopenapi.update=true` after an API change to rewrite it.
 */
class OpenApiExportTest extends ApiTestSupport {
    static final Path FILE = Path.of("openapi.json");

    @Test void committed_openapi_matches_the_served_document() throws Exception {
        var doc = (ObjectNode) json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
        String rendered = normalise(doc);

        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(FILE, rendered, StandardCharsets.UTF_8);
            return;
        }
        assertThat(Files.exists(FILE)).as("server/openapi.json is committed; regenerate it with -Dopenapi.update=true").isTrue();
        assertThat(Files.readString(FILE, StandardCharsets.UTF_8))
                .as("server/openapi.json is out of date — regenerate it with `./mvnw test -Dtest=OpenApiExportTest -Dopenapi.update=true` and commit it")
                .isEqualTo(rendered);
    }

    @Test void every_operation_has_a_unique_operation_id_and_a_tag() throws Exception {
        var doc = json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
        Set<String> seen = new HashSet<>(); List<String> duplicates = new ArrayList<>(); List<String> untagged = new ArrayList<>();
        var paths = doc.get("paths");
        paths.fieldNames().forEachRemaining(path -> paths.get(path).fields().forEachRemaining(op -> {
            var id = op.getValue().get("operationId");
            if (id == null || !seen.add(id.asText())) duplicates.add(op.getKey().toUpperCase() + " " + path + " -> " + (id == null ? "(none)" : id.asText()));
            var tags = op.getValue().get("tags");
            if (tags == null || tags.isEmpty()) untagged.add(op.getKey().toUpperCase() + " " + path);
        }));
        assertThat(duplicates).as("springdoc derives operationId from the method name; rename the colliding ones").isEmpty();
        assertThat(untagged).as("every controller needs an @Tag so the generated client groups its calls").isEmpty();
    }

    /** Sorted keys, no `servers` (it carries the test port), 2-space pretty JSON with a trailing newline. */
    private String normalise(ObjectNode doc) throws Exception {
        doc.remove("servers");
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sorted(doc)) + "\n";
    }

    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>(); node.fieldNames().forEachRemaining(names::add); Collections.sort(names);
            ObjectNode out = mapper.createObjectNode();
            for (String n : names) out.set(n, sorted(node.get(n)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            node.forEach(child -> out.add(sorted(child)));
            return out;
        }
        return node;
    }
}
