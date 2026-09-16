package quest.server.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.ApiTestSupport;

/**
 * The flag endpoints have to cost the same whatever the platform grows to: the column action must not be linear in
 * the number of tenants, and the audit page must not be linear in the number of rows. Measured with Hibernate
 * statistics, the way `ReportsQueryCountTest` measures the reports.
 *
 * <p>The counts are compared against each other rather than against a fixed number: every test class in this suite
 * shares one H2 database, so how many schools and audit rows exist when this class runs depends on the run order.
 * What must hold is the shape — adding four schools or five audit rows changes nothing.
 */
class FlagQueryCountTest extends ApiTestSupport {
    @Autowired jakarta.persistence.EntityManagerFactory emf;

    @Test void the_column_action_costs_the_same_for_two_schools_and_for_six() throws Exception {
        var token = adminToken();
        String a = createSchool(token), b = createSchool(token);

        long twoSchools = statements(() -> setForAll(token, FlagKeys.ANNOUNCEMENTS, true));
        for (int i = 0; i < 4; i++) createSchool(token);
        long sixSchools = statements(() -> setForAll(token, FlagKeys.ANNOUNCEMENTS, false));

        assertThat(sixSchools)
                .as("PUT /admin/flags/{key}/all is two bulk writes and one audit row; four more tenants must not cost four more statements")
                .isEqualTo(twoSchools);

        // …and it really did reach every school, new ones included
        assertThat(json(mvc.perform(get("/schools/" + a + "/flags")).andReturn()).get(FlagKeys.ANNOUNCEMENTS).asBoolean()).isFalse();
        assertThat(json(mvc.perform(get("/schools/" + b + "/flags")).andReturn()).get(FlagKeys.ANNOUNCEMENTS).asBoolean()).isFalse();
        setForAll(token, FlagKeys.ANNOUNCEMENTS, true);
        assertThat(json(mvc.perform(get("/schools/" + a + "/flags")).andReturn()).get(FlagKeys.ANNOUNCEMENTS).asBoolean()).isTrue();
    }

    @Test void the_audit_page_costs_the_same_however_many_rows_it_returns() throws Exception {
        var token = adminToken();
        String school = createSchool(token);
        // two rows before the baseline, so both measurements have an actor to resolve (an empty page skips that
        // lookup altogether, which would make the two counts differ for a reason that is not the N+1)
        setFlag(token, school, FlagKeys.PROGRESS_WEEKLY_EMAIL, true);
        setFlag(token, school, FlagKeys.PROGRESS_WEEKLY_EMAIL, false);
        mvc.perform(admin(get("/admin/flags/audit?limit=200"), token)).andExpect(status().isOk());

        long few = statements(() -> mvc.perform(admin(get("/admin/flags/audit?limit=200"), token)).andExpect(status().isOk()));
        for (int i = 0; i < 5; i++) setFlag(token, school, FlagKeys.PROGRESS_WEEKLY_EMAIL, i % 2 == 0);
        long more = statements(() -> mvc.perform(admin(get("/admin/flags/audit?limit=200"), token)).andExpect(status().isOk()));

        assertThat(more).as("the actor lookup is one findAllById for the page, not one findById per row").isEqualTo(few);
        assertThat(more).as("rows, schools, actors — three statements").isLessThanOrEqualTo(4);
    }

    @Test void the_matrix_costs_the_same_for_two_schools_and_for_six() throws Exception {
        var token = adminToken();
        createSchool(token); createSchool(token);
        long twoSchools = statements(() -> mvc.perform(admin(get("/admin/flags"), token)).andExpect(status().isOk()));
        for (int i = 0; i < 4; i++) createSchool(token);
        long sixSchools = statements(() -> mvc.perform(admin(get("/admin/flags"), token)).andExpect(status().isOk()));
        assertThat(sixSchools).isEqualTo(twoSchools);
        assertThat(sixSchools).as("definitions, schools, overrides").isLessThanOrEqualTo(4);
    }

    private interface Call { void run() throws Exception; }

    private long statements(Call call) throws Exception {
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true); stats.clear();
        call.run();
        return stats.getPrepareStatementCount();
    }

    private void setForAll(String token, String key, boolean enabled) throws Exception {
        mvc.perform(admin(put("/admin/flags/" + key + "/all").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}"), token)).andExpect(status().isOk());
    }

    private void setFlag(String token, String school, String key, boolean enabled) throws Exception {
        mvc.perform(admin(put("/admin/schools/" + school + "/flags/" + key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}"), token)).andExpect(status().isOk());
    }

    private String createSchool(String token) throws Exception {
        var body = "{\"name\":\"Count School " + UUID.randomUUID().toString().substring(0, 6) + "\",\"curriculumOptions\":[\"british\"],\"gradeOptions\":[1,2,3]}";
        return json(mvc.perform(admin(post("/admin/schools").contentType(MediaType.APPLICATION_JSON).content(body), token))
                .andExpect(status().is2xxSuccessful()).andReturn()).get("id").asText();
    }
}
