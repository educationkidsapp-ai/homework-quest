package quest.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import quest.server.api.dto.Enums;
import quest.server.api.dto.Requests;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiTestSupport {
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;

    /** A 1×1 PNG. */
    protected static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    protected Requests.LessonJob createLesson(Enums.Subject subject, String typedTask, boolean withFile) throws Exception {
        var req = new Requests.CreateLessonRequest(subject, 1, "IB PYP", LocalDate.of(2026, 9, 14), 7, typedTask, withFile ? List.of("slide.png") : List.of());
        var builder = MockMvcRequestBuilders.multipart("/lessons")
                .file(new MockMultipartFile("request", "", "application/json", mapper.writeValueAsBytes(req)));
        if (withFile) builder.file(new MockMultipartFile("files", "slide.png", "image/png", PNG));
        String body = mvc.perform(builder).andExpect(r -> assertTrue(r.getResponse().getStatus() == 202, "status " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readValue(body, Requests.LessonJob.class);
    }

    protected Requests.LessonJob getLesson(String id) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.get("/lessons/" + id)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readValue(body, Requests.LessonJob.class);
    }

    protected Requests.LessonJob awaitTerminal(String id) throws Exception {
        for (int i = 0; i < 100; i++) {
            Requests.LessonJob job = getLesson(id);
            if (job.status().isTerminal()) return job;
            Thread.sleep(50);
        }
        throw new AssertionError("lesson " + id + " did not reach a terminal state");
    }
}
