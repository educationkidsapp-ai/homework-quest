package quest.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.api.dto.DevicePlatform;
import quest.api.dto.NotificationKind;
import quest.api.dto.PushMessage;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.ParentRepository;
import quest.server.push.PushSender.Outcome;

/**
 * B4: `POST`/`DELETE /me/devices` — upsert by token, a token moving to whoever registered it last, the cap of ten — and
 * what {@link ParentPush} does with FCM's answers: a dead token is deleted, a transient failure is retried a bounded
 * number of times, and each phone is pushed in its own language.
 */
class DeviceApiTest extends ApiTestSupport {
    private final String OTHER = "Bearer fake-token-parent-other-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    private final java.util.List<String> tokens = new ArrayList<>();

    @Autowired ParentDeviceRepository devices;
    @Autowired ParentRepository parents;
    @Autowired ParentPush push;
    @Autowired PushSender sender;
    @Autowired AdminJwtService jwt;

    @AfterEach void clean() { tokens.forEach(devices::deleteByTokenValue); }

    @Test void a_phone_is_registered_refreshed_and_taken_back() throws Exception {
        String token = token("dv-one");
        register(PARENT, token, "ANDROID", "ar_SA");
        var row = devices.findByToken(token).orElseThrow();
        assertThat(row.getParentId()).isEqualTo(parentId(PARENT));
        assertThat(row.getPlatform()).isEqualTo("ANDROID");
        assertThat(row.getLocale()).as("`_` read as `-`").isEqualTo("ar-SA");
        assertThat(row.getAppVersion()).isEqualTo("1.4.0");

        Thread.sleep(5);
        register(PARENT, token, "IOS", "en");
        var again = devices.findByToken(token).orElseThrow();
        assertThat(again.getId()).as("upsert by token: the same row").isEqualTo(row.getId());
        assertThat(again.getPlatform()).isEqualTo("IOS");
        assertThat(again.getLocale()).isEqualTo("en");
        assertThat(again.getLastSeenAt()).isAfter(row.getLastSeenAt());

        mvc.perform(delete("/me/devices/" + token).header("Authorization", PARENT)).andExpect(status().isNoContent());
        assertThat(devices.findByToken(token)).isEmpty();
        mvc.perform(delete("/me/devices/" + token).header("Authorization", PARENT)).andExpect(status().isNoContent());
    }

    @Test void a_token_belongs_to_whoever_registered_it_last() throws Exception {
        String token = token("dv-shared");
        register(PARENT, token, "ANDROID", null);
        register(OTHER, token, "ANDROID", null);
        assertThat(devices.findByToken(token).orElseThrow().getParentId()).as("the phone is hers now").isEqualTo(parentId(OTHER));
        assertThat(devices.findByParentIdOrderByLastSeenAtDescIdAsc(parentId(PARENT))).isEmpty();

        mvc.perform(delete("/me/devices/" + token).header("Authorization", PARENT)).andExpect(status().isNoContent());
        assertThat(devices.findByToken(token)).as("the first parent cannot sign the second one's phone out").isPresent();
    }

    @Test void a_parent_keeps_her_ten_most_recent_phones() throws Exception {
        for (int i = 0; i <= DeviceService.MAX_DEVICES; i++) { register(PARENT, token("dv-cap"), "ANDROID", null); Thread.sleep(2); }
        var mine = devices.findByParentIdOrderByLastSeenAtDescIdAsc(parentId(PARENT));
        assertThat(mine).hasSize(DeviceService.MAX_DEVICES);
        assertThat(mine).extracting(Entities.ParentDeviceEntity::getToken).doesNotContain(tokens.get(0));
    }

    @Test void only_a_parent_registers_and_only_a_token_is_accepted() throws Exception {
        for (String body : new String[] {"{\"token\":\"\",\"platform\":\"ANDROID\"}", "{\"token\":\"a b\",\"platform\":\"ANDROID\"}",
                "{\"token\":\"abc\",\"platform\":\"WINDOWS\"}", "{\"token\":\"abc\",\"platform\":\"IOS\",\"locale\":\"not a locale\"}", "nope"})
            mvc.perform(post("/me/devices").header("Authorization", PARENT).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        String body = "{\"token\":\"dv-staff\",\"platform\":\"ANDROID\"}";
        mvc.perform(post("/me/devices").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        String teacher = jwt.issue("dv-teacher", "dv-teacher@test.local", "TEACHER", "default").token();
        mvc.perform(post("/me/devices").header("Authorization", "Bearer " + teacher).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test void a_dead_token_is_deleted_and_a_live_one_kept() throws Exception {
        String dead = token("dv-dead"), live = token("dv-live");
        register(PARENT, dead, "ANDROID", null); register(PARENT, live, "ANDROID", null);
        PushProbe.script(sender, dead, Outcome.DEAD_TOKEN);
        push.toParent(parentId(PARENT), message("Hello"), null);
        assertThat(PushProbe.await(sender, live, 1)).hasSize(1);
        assertThat(devices.findByToken(dead)).as("FCM said UNREGISTERED").isEmpty();
        assertThat(devices.findByToken(live)).isPresent();
    }

    @Test void a_transient_failure_is_retried_and_then_given_up() throws Exception {
        String flaky = token("dv-flaky"), down = token("dv-down");
        register(PARENT, flaky, "ANDROID", null);
        PushProbe.script(sender, flaky, Outcome.RETRY, Outcome.RETRY);
        push.toParent(parentId(PARENT), message("Third time lucky"), null);
        assertThat(PushProbe.await(sender, flaky, 1)).as("two failures, then sent").hasSize(1);

        register(OTHER, down, "ANDROID", null);
        PushProbe.script(sender, down, Outcome.RETRY, Outcome.RETRY, Outcome.RETRY, Outcome.RETRY);
        push.toParent(parentId(OTHER), message("Never"), null);
        assertThat(PushProbe.await(sender, down, 1)).as("three attempts, all failed: dropped").isEmpty();
        assertThat(devices.findByToken(down)).as("a failure is not a dead token").isPresent();
    }

    @Test void each_phone_is_pushed_in_its_own_language() throws Exception {
        String ar = token("dv-ar"), en = token("dv-en");
        register(PARENT, ar, "ANDROID", "ar"); register(PARENT, en, "IOS", "en-GB");
        push.toParent(parentId(PARENT), message("Hello"), message("مرحبا"));
        var arabic = PushProbe.await(sender, ar, 1);
        assertThat(arabic).singleElement().satisfies(s -> assertThat(s.message().getTitle()).isEqualTo("مرحبا"));
        assertThat(PushProbe.await(sender, en, 1)).singleElement().satisfies(s -> {
            assertThat(s.message().getTitle()).isEqualTo("Hello");
            assertThat(s.platform()).isEqualTo(DevicePlatform.IOS);
        });
    }

    private void register(String bearer, String token, String platform, String locale) throws Exception {
        String body = "{\"token\":\"" + token + "\",\"platform\":\"" + platform + "\",\"appVersion\":\"1.4.0\""
                + (locale == null ? "" : ",\"locale\":\"" + locale + "\"") + "}";
        mvc.perform(post("/me/devices").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    private String token(String label) { String t = PushProbe.token(label); tokens.add(t); return t; }

    private String parentId(String bearer) {
        return parents.findByFirebaseUid(bearer.substring("Bearer fake-token-".length())).orElseThrow().getId();
    }

    private static PushMessage message(String title) {
        return new PushMessage(NotificationKind.CHAT_MESSAGE, title, null, "n-1", "c-1", "/children/c-1/chat/t-1", null, "chat:th-1");
    }
}
