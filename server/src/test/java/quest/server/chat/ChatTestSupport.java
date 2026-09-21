package quest.server.chat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.ClassFixtures;
import quest.server.flags.FlagKeys;
import quest.server.grading.GradingTestSupport;
import quest.server.tenancy.Entities.ClassEntity;

/**
 * C1's fixture on top of {@link GradingTestSupport}'s school, teachers and sections: school A has the `chat` flag on
 * and school B has it off; Sara teaches 1A math and Noor 1A english (both may talk to a 1A parent), Noor also
 * teaches 1B alone; `Other` is a teacher of school B. Children are created through the parent API so their parent
 * row is real, and every row carries the `ch-` prefix so {@link #removeSeed} takes it out of the shared database.
 */
public abstract class ChatTestSupport extends GradingTestSupport {
    static final String A = "ch-school-a", B = "ch-school-b";
    static final String SARA = "ch-teacher-sara", NOOR = "ch-teacher-noor", OTHER = "ch-teacher-other";
    static final String CLASS_1A = "ch-1a", CLASS_1B = "ch-1b", CLASS_OTHER = "ch-other";

    @Autowired ChatThreadRepository threadRows;
    @Autowired ChatMessageRepository messageRows;

    protected final String OTHER_PARENT = "Bearer fake-token-parent-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    protected String adminToken, sara, noor, other;
    protected ClassEntity section1a, section1b;

    @Override public String prefix() { return "ch-"; }

    protected void seedSchools() throws Exception {
        school(A, "Chat Academy", "CHSCHA"); school(B, "Quiet Academy", "CHSCHB");
        teacher(SARA, A, "Ms Sara"); teacher(NOOR, A, "Ms Noor"); teacher(OTHER, B, "Ms Other");
        section1a = klass(CLASS_1A, A, SARA, "1A");
        ClassFixtures.assign(assignments, section1a, "english", NOOR);
        section1b = klass(CLASS_1B, A, NOOR, "1B");
        klass(CLASS_OTHER, B, OTHER, "1A");
        adminToken = adminToken();
        setFlag(adminToken, A, FlagKeys.CHAT, true);
        setFlag(adminToken, B, FlagKeys.CHAT, false);
        sara = token(SARA, "TEACHER", A); noor = token(NOOR, "TEACHER", A); other = token(OTHER, "TEACHER", B);
    }

    /** A child of the test's parent with no section yet (the `409 child_not_placed` case). */
    protected String unplacedChild(String name) throws Exception {
        return parentPost("/children", "{\"name\":\"" + name + "\",\"avatarColor\":\"sun\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"CHSCHA\"}").get("id").asText();
    }

    /** A child of a different parent, on 1A. */
    protected String someoneElsesChild(String name) throws Exception {
        String id = json(mvc.perform(post("/children").header("Authorization", OTHER_PARENT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"avatarColor\":\"sun\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"CHSCHA\"}"))
                .andExpect(status().is2xxSuccessful()).andReturn()).get("id").asText();
        childRows.findById(id).ifPresent(c -> { c.setClassId(section1a.getId()); childRows.save(c); });
        return id;
    }

    static String send(String body) { return "{\"body\":" + quote(body) + "}"; }
    static String send(String body, String clientId) { return "{\"body\":" + quote(body) + ",\"clientId\":\"" + clientId + "\"}"; }
    static String quote(String s) {
        var sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    @Override public void removeSeed() {
        var threads = threadRows.findAll().stream().filter(t -> t.getSchoolId().startsWith(prefix())).toList();
        messageRows.deleteAll(messageRows.findAll().stream().filter(m -> m.getSchoolId().startsWith(prefix())).toList());
        threadRows.deleteAll(threads);
        super.removeSeed();
    }
}
