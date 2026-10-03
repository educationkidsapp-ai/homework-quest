package quest.server.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import quest.server.push.ParentDeviceRepository;
import quest.server.push.PushProbe;
import quest.server.push.PushSender;
import quest.server.tenancy.TenantContext;

/**
 * B5: real files on chat messages, on every transport — the parent's app, a teacher by child, a manager, a coordinator,
 * a teacher's staff thread and the Admin's own thread — and the one rule for reading them: the thread's participants,
 * and a 404 that looks like an unknown id for everybody else (another parent, another teacher, another school).
 */
class ChatAttachmentsApiTest extends ChatTestSupport {
    static final String MANAGER = "ch-manager-nour", COORDINATOR = "ch-coord-lina";
    @Autowired quest.server.tenancy.StaffScopeRepository staffScopes;
    @Autowired quest.server.files.AttachmentRepository attachmentRows;
    @Autowired quest.server.notifications.NotificationRepository notificationRows;
    @Autowired PushSender pushes;
    @Autowired ParentDeviceRepository devices;

    private String maya, manager, coordinator;

    @BeforeEach void seed() throws Exception {
        seedSchools();
        maya = child("Maya", "CHSCHA", section1a);
        manager = staff(MANAGER, "MANAGERIAL", null);
        coordinator = staff(COORDINATOR, "COORDINATOR", "math");
    }

    @AfterEach void clean() {
        attachmentRows.deleteAll(attachmentRows.findAll().stream().filter(a -> a.getSchoolId().startsWith(prefix())).toList());
        notificationRows.deleteAll(notificationRows.findAll().stream().filter(n -> n.getSchoolId().startsWith(prefix())).toList());
        staffScopes.deleteAll(staffScopes.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        removeSeed();
    }

    // ---------------------------------------------------------------- the parent and the teacher

    @Test void a_parent_sends_a_photo_and_only_the_threads_participants_can_open_it() throws Exception {
        var uploaded = json(mvc.perform(parent(parentUpload(maya, png(40, 30), "photo.png")))
                .andExpect(status().isCreated()).andReturn());
        String id = uploaded.get("id").asText();
        assertThat(uploaded.get("type").asText()).isEqualTo("image/png");
        assertThat(uploaded.get("width").asInt()).isEqualTo(40);
        assertThat(uploaded.get("height").asInt()).isEqualTo(30);
        mvc.perform(parent(get("/media/attachments/" + id))).andExpect(status().isOk());              // her own, before she sends it
        mvc.perform(as(get("/media/attachments/" + id), sara)).andExpect(status().isNotFound());

        var sent = parentPost("/children/" + maya + "/chat/threads/" + SARA + "/messages", "{\"attachmentIds\":[\"" + id + "\"],\"clientId\":\"c-1\"}");
        assertThat(sent.get("body").asText()).as("files alone: no text").isEmpty();
        var file = sent.get("attachments").get(0);
        assertThat(file.get("id").asText()).isEqualTo(id);
        assertThat(file.get("contentType").asText()).isEqualTo("image/png");
        assertThat(file.get("name").asText()).isEqualTo("photo.png");
        assertThat(file.get("size").asLong()).isPositive();
        assertThat(file.get("width").asInt()).isEqualTo(40);

        // history, both thread lists' previews
        assertThat(parentGet("/children/" + maya + "/chat/threads/" + SARA + "/messages").get(0).get("attachments").get(0).get("id").asText()).isEqualTo(id);
        var teacherRow = json(mvc.perform(as(get("/teacher/chat/threads"), sara)).andExpect(status().isOk()).andReturn()).get(0);
        assertThat(teacherRow.get("lastMessage").get("attachments").get(0).get("id").asText()).isEqualTo(id);
        // the teacher's bell says what it is
        assertThat(json(mvc.perform(as(get("/me/notifications"), sara)).andReturn()).get(0).get("body").asText()).isEqualTo("📷 Photo");

        // the participants read it, inline, never sniffed, never shared-cached
        mvc.perform(as(get("/media/attachments/" + id), sara)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"photo.png\""))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")));
        mvc.perform(parent(get("/media/attachments/" + id))).andExpect(status().isOk());
        // everybody else: Noor teaches Maya too but is not on this thread, another parent, another school, the Admin off it
        mvc.perform(as(get("/media/attachments/" + id), noor)).andExpect(status().isNotFound());
        mvc.perform(get("/media/attachments/" + id).header("Authorization", OTHER_PARENT)).andExpect(status().isNotFound());
        mvc.perform(as(get("/media/attachments/" + id), other)).andExpect(status().isNotFound());
        mvc.perform(as(get("/media/attachments/" + id), adminToken).header(TenantContext.HEADER, A)).andExpect(status().isNotFound());
        String unknown = json(mvc.perform(as(get("/media/attachments/no-such-file"), noor)).andReturn()).toString();
        assertThat(json(mvc.perform(as(get("/media/attachments/" + id), noor)).andReturn()).toString()).as("the same 404 as an unknown id").isEqualTo(unknown);

        // a file goes with one message
        mvc.perform(parent(post("/children/" + maya + "/chat/threads/" + SARA + "/messages")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"attachmentIds\":[\"" + id + "\"]}")).andExpect(status().isBadRequest());
    }

    /** The push is what the parent sees on her lock screen: "📄 plan.pdf", and "📷 صورة" on an Arabic phone. */
    @Test void a_teacher_sends_files_alone_and_the_parent_is_told_what_they_are() throws Exception {
        String phone = PushProbe.token("ch-b5-phone"), arabic = PushProbe.token("ch-b5-ar");
        PushProbe.register(mvc, PARENT, phone, "en");
        PushProbe.register(mvc, PARENT, arabic, "ar");
        String photo = staffUpload(sara, png(20, 10), "board.png", null);
        var sent = json(mvc.perform(as(post("/teacher/chat/threads/" + maya + "/messages"), sara).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"  \",\"attachmentIds\":[\"" + photo + "\"]}")).andExpect(status().isCreated()).andReturn());
        assertThat(sent.get("body").asText()).isEmpty();
        assertThat(PushProbe.await(pushes, phone, 1)).singleElement().satisfies(p -> assertThat(p.message().getBody()).isEqualTo("📷 Photo"));
        assertThat(PushProbe.await(pushes, arabic, 1)).singleElement().satisfies(p -> assertThat(p.message().getBody()).isEqualTo("📷 صورة"));
        assertThat(parentGet("/me/notifications").get(0).get("body").asText()).isEqualTo("📷 Photo");
        mvc.perform(parent(get("/media/attachments/" + photo))).andExpect(status().isOk());

        parentPost("/children/" + maya + "/chat/threads/" + SARA + "/read", "");
        String pdf = staffUpload(sara, "%PDF-1.7\n%%EOF".getBytes(), "Plan.pdf", null);
        mvc.perform(as(post("/teacher/chat/threads/" + maya + "/messages"), sara).contentType(MediaType.APPLICATION_JSON)
                .content("{\"attachmentIds\":[\"" + pdf + "\"]}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments[0].contentType").value("application/pdf"))
                .andExpect(jsonPath("$.attachments[0].width").doesNotExist());
        assertThat(PushProbe.await(pushes, arabic, 2).get(1).message().getBody()).isEqualTo("📄 plan.pdf");
        mvc.perform(parent(get("/media/attachments/" + pdf))).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"plan.pdf\""));
        devices.deleteByTokenValue(phone); devices.deleteByTokenValue(arabic);
    }

    // ---------------------------------------------------------------- every staff transport

    @Test void every_staff_transport_sends_files_its_peer_can_open() throws Exception {
        // the manager, on a parent thread of her department
        String parentThread = thread("/management/chat/threads", manager, "{\"childId\":\"" + maya + "\"}");
        String a = staffUpload(manager, png(8, 8), "a.png", null);
        sendOn("/management/chat/threads/" + parentThread + "/messages", manager, a);
        mvc.perform(parent(get("/media/attachments/" + a))).andExpect(status().isOk());
        mvc.perform(as(get("/media/attachments/" + a), sara)).andExpect(status().isNotFound());

        // the coordinator, to her manager
        String coordThread = thread("/coordinator/chat/threads", coordinator, "{\"managerUserId\":\"" + MANAGER + "\"}");
        String b = staffUpload(coordinator, png(8, 8), "b.png", null);
        sendOn("/coordinator/chat/threads/" + coordThread + "/messages", coordinator, b);
        mvc.perform(as(get("/media/attachments/" + b), manager)).andExpect(status().isOk());
        mvc.perform(parent(get("/media/attachments/" + b))).andExpect(status().isNotFound());

        // the teacher, on her staff thread with the manager
        String staffThread = thread("/teacher/chat/staff-threads", sara, "{\"managerUserId\":\"" + MANAGER + "\"}");
        String c = staffUpload(sara, png(8, 8), "c.png", null);
        sendOn("/teacher/chat/staff-threads/" + staffThread + "/messages", sara, c);
        mvc.perform(as(get("/media/attachments/" + c), manager)).andExpect(status().isOk());
        mvc.perform(as(get("/media/attachments/" + c), noor)).andExpect(status().isNotFound());

        // the Admin, on her own thread with the parent — `X-School-Id` names the school, as for her every chat write
        String adminThread = json(mvc.perform(as(post("/admin/chat/threads"), adminToken).header(TenantContext.HEADER, A)
                .contentType(MediaType.APPLICATION_JSON).content("{\"childId\":\"" + maya + "\"}")).andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(as(chatUpload(png(8, 8), "d.png"), adminToken)).andExpect(status().is4xxClientError());   // no school named
        String d = staffUpload(adminToken, png(8, 8), "d.png", A);
        mvc.perform(as(post("/admin/chat/threads/" + adminThread + "/messages"), adminToken).header(TenantContext.HEADER, A)
                .contentType(MediaType.APPLICATION_JSON).content("{\"attachmentIds\":[\"" + d + "\"]}")).andExpect(status().isCreated());
        mvc.perform(parent(get("/media/attachments/" + d))).andExpect(status().isOk());
        mvc.perform(as(get("/media/attachments/" + d), adminToken).header(TenantContext.HEADER, A)).andExpect(status().isOk());
        // the parent answers with a file of her own, which the Admin opens
        var mine = json(mvc.perform(parent(parentUpload(maya, png(8, 8), "e.png"))).andReturn()).get("id").asText();
        String adminId = json(mvc.perform(as(get("/admin/chat/threads").param("mine", "true"), adminToken).header(TenantContext.HEADER, A)).andReturn())
                .get(0).get("teacherId").asText();
        parentPost("/children/" + maya + "/chat/threads/" + adminId + "/messages", "{\"attachmentIds\":[\"" + mine + "\"]}");
        mvc.perform(as(get("/media/attachments/" + mine), adminToken)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- what is refused

    @Test void the_limits_and_the_refusals() throws Exception {
        // the type is the bytes', and the sizes are MH1's
        mvc.perform(parent(parentUpload(maya, "hello".getBytes(), "note.png"))).andExpect(status().isBadRequest());
        byte[] big = new byte[5 * 1024 * 1024 + 1]; System.arraycopy(png(1, 1), 0, big, 0, 8);
        mvc.perform(parent(parentUpload(maya, big, "big.png"))).andExpect(status().isPayloadTooLarge());
        byte[] bigPdf = new byte[10 * 1024 * 1024 + 1]; System.arraycopy("%PDF-".getBytes(), 0, bigPdf, 0, 5);
        mvc.perform(parent(parentUpload(maya, bigPdf, "big.pdf"))).andExpect(status().isPayloadTooLarge());
        // a parent uploads for a conversation about her own child, and through her own route only
        mvc.perform(parent(upload(png(2, 2), "x.png"))).andExpect(status().isForbidden());
        mvc.perform(parent(chatUpload(png(2, 2), "x.png"))).andExpect(status().isNotFound());   // the flag fails closed for a parent with no child in the path
        mvc.perform(parent(parentUpload(someoneElsesChild("Theirs"), png(2, 2), "x.png"))).andExpect(status().isNotFound());
        mvc.perform(as(chatUpload(png(2, 2), "x.png"), other)).andExpect(status().isNotFound());   // school B has chat off

        // a send names only the sender's own chat uploads, at most five, and says something or sends something
        String saras = staffUpload(sara, png(2, 2), "s.png", null);
        String broadcastImage = json(mvc.perform(as(upload(png(2, 2), "b.png"), sara)).andExpect(status().isCreated()).andReturn()).get("id").asText();
        String path = "/children/" + maya + "/chat/threads/" + SARA + "/messages";
        for (String body : new String[] {"{\"attachmentIds\":[\"" + saras + "\"]}", "{\"attachmentIds\":[\"no-such-id\"]}", "{\"body\":\"\"}",
                "{\"attachmentIds\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]}"})
            mvc.perform(parent(post(path)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(as(post("/teacher/chat/threads/" + maya + "/messages"), sara).contentType(MediaType.APPLICATION_JSON)
                .content("{\"attachmentIds\":[\"" + broadcastImage + "\"]}")).andExpect(status().isBadRequest());
        // …and a chat upload cannot become a broadcast's attachment either
        setFlag(adminToken, A, quest.server.flags.FlagKeys.ANNOUNCEMENTS, true);
        mvc.perform(as(post("/management/broadcasts"), manager).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"announcement\",\"title\":\"Hi\",\"bodyEn\":\"Hello\",\"audience\":[\"parents\"],\"attachmentId\":\"" + staffUpload(manager, png(2, 2), "m.png", null) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not one of yours")));
        assertThat(messageRows.findAll().stream().filter(m -> m.getSchoolId().startsWith(prefix()))).as("nothing refused was written").isEmpty();
    }

    /** `?w=` is a smaller JPEG for a chat bubble; asking for one of a PDF answers the PDF. */
    @Test void a_downscaled_copy_is_a_jpeg_of_the_asked_width() throws Exception {
        String photo = staffUpload(sara, png(400, 300), "wide.png", null);
        mvc.perform(as(post("/teacher/chat/threads/" + maya + "/messages"), sara).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Look\",\"attachmentIds\":[\"" + photo + "\"]}")).andExpect(status().isCreated());
        var r = mvc.perform(parent(get("/media/attachments/" + photo).param("w", "100"))).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg")).andReturn();
        var small = ImageIO.read(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()));
        assertThat(small.getWidth()).isEqualTo(100);
        assertThat(small.getHeight()).isEqualTo(75);
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder parent(MockHttpServletRequestBuilder b) { return b.header("Authorization", PARENT); }

    /** MH1's broadcast upload, which a chat send refuses. */
    private static MockMultipartHttpServletRequestBuilder upload(byte[] bytes, String name) { return multipart("/media/attachments").file(file(bytes, name)); }
    private static MockMultipartHttpServletRequestBuilder chatUpload(byte[] bytes, String name) { return multipart("/media/chat-attachments").file(file(bytes, name)); }
    private static MockMultipartHttpServletRequestBuilder parentUpload(String childId, byte[] bytes, String name) {
        return multipart("/children/" + childId + "/chat/attachments").file(file(bytes, name));
    }
    private static MockMultipartFile file(byte[] bytes, String name) { return new MockMultipartFile("file", name, "application/octet-stream", bytes); }

    private String staffUpload(String token, byte[] bytes, String name, String school) throws Exception {
        var request = as(chatUpload(bytes, name), token);
        if (school != null) request = request.header(TenantContext.HEADER, school);
        return json(mvc.perform(request).andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    private String thread(String path, String token, String body) throws Exception {
        return json(mvc.perform(as(post(path), token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn()).get("id").asText();
    }

    private JsonNode sendOn(String path, String token, String attachmentId) throws Exception {
        var sent = json(mvc.perform(as(post(path), token).contentType(MediaType.APPLICATION_JSON).content("{\"attachmentIds\":[\"" + attachmentId + "\"]}"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(sent.get("attachments").get(0).get("id").asText()).isEqualTo(attachmentId);
        return sent;
    }

    private static byte[] png(int w, int h) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /** A school-A staff member of the British department: a manager (no subject) or a coordinator of one subject. */
    private String staff(String userId, String role, String subject) {
        var u = users.findById(userId).orElseGet(quest.server.auth.Entities.UserEntity::new);
        u.setId(userId); u.setSchoolId(A); u.setEmail(userId + "@seed.test"); u.setPasswordHash("x");
        u.setRole(role); u.setStatus("active"); u.setDisplayName(userId);
        if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        users.save(u);
        var row = staffScopes.findById(userId + ":british").orElseGet(quest.server.tenancy.Entities.StaffScopeEntity::new);
        row.setId(userId + ":british"); row.setSchoolId(A); row.setUserId(userId); row.setSubject(subject); row.setCurriculum("british");
        if (row.getCreatedAt() == null) row.setCreatedAt(Instant.now());
        staffScopes.save(row);
        return token(userId, role, A);
    }
}
