package quest.server.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Thin unchecked wrapper over the Spring-configured ObjectMapper. */
public class Json {
    private final ObjectMapper mapper;
    public Json(ObjectMapper mapper) { this.mapper = mapper; }

    public String write(Object o) {
        try { return mapper.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    public <T> T read(String s, Class<T> type) {
        try { return mapper.readValue(s, type); } catch (JsonProcessingException e) { throw new IllegalArgumentException("bad json: " + e.getOriginalMessage(), e); }
    }

    public <T> T read(String s, TypeReference<T> type) {
        try { return mapper.readValue(s, type); } catch (JsonProcessingException e) { throw new IllegalArgumentException("bad json: " + e.getOriginalMessage(), e); }
    }

    public List<String> strings(String s) { return read(s, new TypeReference<List<String>>() {}); }
}
