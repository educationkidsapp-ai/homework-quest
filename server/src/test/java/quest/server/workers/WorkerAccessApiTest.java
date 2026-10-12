package quest.server.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.UserRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;

/**
 * Feature 13: the login a worker never had. The Admin enables it once and reads the username and the temporary
 * password out of that one answer; the account signs in to the app with `mustChangePassword`; a reset replaces the
 * password and kills the old one; retiring her disables the account. A MANAGERIAL user may read the Workers page and
 * may not mint an account on it, and a worker of another school is simply not found.
 */
class WorkerAccessApiTest extends ApiTestSupport {
    private static final String OTHER_SCHOOL = "wk-school-b";

    @Autowired WorkerRepository workers;
    @Autowired UserRepository users;
    @Autowired SchoolRepository schools;
    @Autowired AdminJwtService jwt;

    private String admin, workerId;

    @BeforeEach void seed() throws Exception {
        admin = adminToken();
        workerId = json(mvc.perform(post("/admin/workers").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Hassan Ali\",\"job\":\"Driver\",\"phone\":\"+201000000011\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    /** Workers and the accounts behind them: the suite shares one H2 database. */
    @AfterEach void clean() {
        workers.findAll().stream().filter(w -> "Driver".equals(w.getJob()) || OTHER_SCHOOL.equals(w.getSchoolId())).forEach(w -> {
            String account = w.getUserId();
            workers.delete(w);                                                  // the FK points this way round
            if (account != null) users.findById(account).ifPresent(users::delete);
        });
    }

    @Test void app_access_is_enabled_once_and_answers_the_username_and_the_password() throws Exception {
        var access = enable().andExpect(status().isCreated()).andReturn();
        var body = json(access);
        String username = body.get("username").asText(), password = body.get("temporaryPassword").asText();
        assertThat(username).as("`w.<first name>.<4 digits>`").matches("w\\.hassan\\.\\d{4}");
        assertThat(password).hasSize(12);

        enable().andExpect(status().isConflict());

        var page = json(mvc.perform(get("/admin/workers").header("Authorization", "Bearer " + admin)).andExpect(status().isOk()).andReturn());
        var hers = java.util.stream.StreamSupport.stream(page.spliterator(), false)
                .filter(w -> workerId.equals(w.get("id").asText())).findFirst().orElseThrow();
        assertThat(hers.get("hasAppAccess").asBoolean()).isTrue();
        assertThat(hers.get("username").asText()).as("the Admin's page shows what she types, never the password").isEqualTo(username);

        var session = json(mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + username + "\",\"password\":\"" + password + "\"}")).andExpect(status().isOk()).andReturn());
        assertThat(session.get("role").asText()).isEqualTo("WORKER");
        assertThat(session.get("mustChangePassword").asBoolean()).isTrue();
        assertThat(session.get("displayName").asText()).isEqualTo("Hassan Ali");
    }

    @Test void a_reset_replaces_the_password_and_the_old_one_stops_working() throws Exception {
        var first = json(enable().andExpect(status().isCreated()).andReturn());
        String username = first.get("username").asText();

        var again = json(mvc.perform(post("/admin/workers/" + workerId + "/reset-password").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn());
        assertThat(again.get("username").asText()).as("the login does not change").isEqualTo(username);
        assertThat(again.get("temporaryPassword").asText()).isNotEqualTo(first.get("temporaryPassword").asText());

        signIn(username, first.get("temporaryPassword").asText()).andExpect(status().isUnauthorized());
        signIn(username, again.get("temporaryPassword").asText()).andExpect(status().isOk());
    }

    @Test void retiring_her_disables_the_account() throws Exception {
        var access = json(enable().andExpect(status().isCreated()).andReturn());
        mvc.perform(delete("/admin/workers/" + workerId).header("Authorization", "Bearer " + admin)).andExpect(status().isNoContent());
        signIn(access.get("username").asText(), access.get("temporaryPassword").asText()).andExpect(status().isUnauthorized());
        var row = workers.findById(workerId).orElseThrow();
        assertThat(users.findById(row.getUserId()).orElseThrow().getStatus()).isEqualTo("disabled");
    }

    @Test void only_an_admin_mints_an_account_and_only_in_her_own_school() throws Exception {
        String manager = jwt.issue("wk-manager", "wk-manager@test.local", "MANAGERIAL", "default").token();
        mvc.perform(post("/admin/workers/" + workerId + "/app-access").header("Authorization", "Bearer " + manager))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/workers/" + workerId + "/reset-password").header("Authorization", "Bearer " + manager))
                .andExpect(status().isForbidden());

        mvc.perform(post("/admin/workers/" + elsewhere() + "/app-access").header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        // And her account cannot be reset either: she is not in the scope the Admin is acting in.
        mvc.perform(post("/admin/workers/" + elsewhere() + "/reset-password").header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
    }

    @Test void a_worker_with_no_account_has_no_password_to_reset() throws Exception {
        mvc.perform(post("/admin/workers/" + workerId + "/reset-password").header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions enable() throws Exception {
        return mvc.perform(post("/admin/workers/" + workerId + "/app-access").header("Authorization", "Bearer " + admin));
    }

    private org.springframework.test.web.servlet.ResultActions signIn(String login, String password) throws Exception {
        return mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + login + "\",\"password\":\"" + password + "\"}"));
    }

    /** A worker of another school, written straight into the table: no route of the Admin's scope could create her. */
    private String elsewhere() {
        schools.findById(OTHER_SCHOOL).orElseGet(() -> {
            var s = new SchoolEntity();
            s.setId(OTHER_SCHOOL); s.setName("Other School"); s.setCode("WKSCHB");
            s.setCurriculumOptionsJson("[\"british\"]"); s.setGradeOptionsJson("[1]"); s.setStatus("active"); s.setCreatedAt(Instant.now());
            return schools.save(s);
        });
        var row = new Entities.WorkerEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(OTHER_SCHOOL); row.setFullName("Samir Nour");
        row.setJob("Driver"); row.setActive(true); row.setCreatedAt(Instant.now());
        return workers.save(row).getId();
    }
}
