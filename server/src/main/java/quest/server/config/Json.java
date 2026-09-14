package quest.server.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import kotlinx.serialization.KSerializer;
import org.springframework.stereotype.Component;
import quest.api.validation.SchemaValidator;

/**
 * Two JSON worlds meet here: Jackson for the server's own records and raw JSON assembly, and the shared
 * kotlinx-serialization codec (same settings as the app) for the contract types in shared-api.
 */
@Component
public class Json {
    private final ObjectMapper mapper;
    public Json(ObjectMapper mapper) { this.mapper = mapper; }

    public ObjectMapper mapper() { return mapper; }
    public ObjectNode object() { return mapper.createObjectNode(); }
    public ArrayNode array() { return mapper.createArrayNode(); }

    public String write(Object o) { try { return mapper.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    public JsonNode tree(String s) { try { return mapper.readTree(s); } catch (JsonProcessingException e) { throw new IllegalArgumentException("bad json: " + e.getOriginalMessage(), e); } }
    public <T> T read(String s, Class<T> type) { try { return mapper.readValue(s, type); } catch (JsonProcessingException e) { throw new IllegalArgumentException("bad json: " + e.getOriginalMessage(), e); } }
    public <T> T read(String s, TypeReference<T> type) { try { return mapper.readValue(s, type); } catch (JsonProcessingException e) { throw new IllegalArgumentException("bad json: " + e.getOriginalMessage(), e); } }
    public List<String> strings(String s) { return read(s, new TypeReference<List<String>>() {}); }

    /** Decode a shared-api contract type with the shared codec. */
    public <T> T decodeShared(String json, KSerializer<T> serializer) { return SchemaValidator.INSTANCE.getJson().decodeFromString(serializer, json); }
    public <T> String encodeShared(T value, KSerializer<T> serializer) { return SchemaValidator.INSTANCE.getJson().encodeToString(serializer, value); }
}
