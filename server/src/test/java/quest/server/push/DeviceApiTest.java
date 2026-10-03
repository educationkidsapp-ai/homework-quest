package quest.server.push;

import static org.assertj.core.api.Assertions.assertThat;
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
 * B4: `POST /me/devices` and `POST /me/devices/unregister` — upsert by token, a token moving to whoever registered it last, the cap of ten — and
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
    @Autowired jakarta.persistence.EntityManagerFactory emf;

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

        unregister(PARENT, token);
        assertThat(devices.findByToken(token)).isEmpty();
        unregister(PARENT, token);
    }

    @Test void a_token_belongs_to_whoever_registered_it_last() throws Exception {
        String token = token("dv-shared");
        register(PARENT, token, "ANDROID", null);
        register(OTHER, token, "ANDROID", null);
        assertThat(devices.findByToken(token).orElseThrow().getParentId()).as("the phone is hers now").isEqualTo(parentId(OTHER));
        assertThat(devices.findByParentIdOrderByLastSeenAtDescIdAsc(parentId(PARENT))).isEmpty();

        unregister(PARENT, token);
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

    /** One fan-out is one read of its parents' phones, however many parents it reaches (no read per parent). */
    @Test void a_fan_out_reads_every_parents_phones_in_one_statement() throws Exception {
        var deliveries = new ArrayList<ParentPush.Delivery>();
        for (int i = 0; i < 5; i++) {
            String bearer = "Bearer fake-token-parent-fan-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            String phone = token("dv-fan");
            register(bearer, phone, "ANDROID", null);
            deliveries.add(new ParentPush.Delivery(parentId(bearer), message("Fan " + i), null));
        }
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true); stats.clear();
        push.toParents(deliveries.subList(0, 1));
        long one = stats.getPrepareStatementCount();
        stats.clear();
        push.toParents(deliveries);
        assertThat(stats.getPrepareStatementCount()).as("five parents cost what one does").isEqualTo(one).isEqualTo(1);
        for (String t : tokens.subList(tokens.size() - 5, tokens.size())) assertThat(PushProbe.await(sender, t, 1)).isNotEmpty();
    }

    /** The retry pause grows fourfold and is jittered ±50 %, so one burst's retries do not return together. */
    @Test void the_backoff_grows_and_is_jittered() throws Exception {
        var standalone = new ParentPush(null, devices, sender, new PushProperties(false, 3, 1000L, 2, 10));
        try {
            for (int i = 0; i < 50; i++) {
                assertThat(standalone.backoff(1)).isBetween(500L, 1500L);
                assertThat(standalone.backoff(2)).isBetween(2000L, 6000L);
            }
            assertThat(java.util.stream.IntStream.range(0, 50).mapToObj(i -> standalone.backoff(2)).distinct().count()).as("jittered").isGreaterThan(1);
        } finally { standalone.destroy(); }
    }

    private void register(String bearer, String token, String platform, String locale) throws Exception {
        String body = "{\"token\":\"" + token + "\",\"platform\":\"" + platform + "\",\"appVersion\":\"1.4.0\""
                + (locale == null ? "" : ",\"locale\":\"" + locale + "\"") + "}";
        mvc.perform(post("/me/devices").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    private void unregister(String bearer, String token) throws Exception {
        mvc.perform(post("/me/devices/unregister").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}")).andExpect(status().isNoContent());
    }

    private String token(String label) { String t = PushProbe.token(label); tokens.add(t); return t; }

    private String parentId(String bearer) {
        return parents.findByFirebaseUid(bearer.substring("Bearer fake-token-".length())).orElseThrow().getId();
    }

    private static PushMessage message(String title) {
        return new PushMessage(NotificationKind.CHAT_MESSAGE, title, null, "n-1", "c-1", "/children/c-1/chat/t-1", null, "chat:th-1", null, null);
    }
}
