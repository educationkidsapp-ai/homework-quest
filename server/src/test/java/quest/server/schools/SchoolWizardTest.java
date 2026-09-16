package quest.server.schools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.ApiTestSupport;
import quest.server.auth.UserRepository;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.SchoolRepository;

/**
 * §6 screen 4: the New school wizard's submit. One transaction — school, theme, flags and the first Managerial user —
 * and nothing left behind when any step is refused.
 */
class SchoolWizardTest extends ApiTestSupport {
    /** A theme whose contrast is fine, so only the step under test can fail. */
    private static final String GOOD_THEME = """
            {"logoUrl":"https://cdn.example.test/wizard.png","appName":"Wizard School","primary":"#FFFFFF",
             "primaryInk":"#201E1D","accent":"#CC2A0F","ground":"#F3F2F2","softBorder":"#D9D6D2",
             "mascotColor":"#41708F","fontChoice":"nunito"}""";
    /** White on white: §3's 4.5:1 rule refuses it and names the pair. */
    private static final String BAD_THEME = """
            {"appName":"Invisible","primary":"#FFFFFF","primaryInk":"#FEFEFE","accent":"#CC2A0F","ground":"#F3F2F2",
             "softBorder":"#D9D6D2","mascotColor":"#41708F","fontChoice":"nunito"}""";

    @Autowired SchoolRepository schools;
    @Autowired UserRepository users;

    @Test void the_wizard_creates_the_school_its_theme_its_flags_and_its_first_manager() throws Exception {
        var token = adminToken();
        var made = json(mvc.perform(admin(post("/admin/schools/wizard").contentType(MediaType.APPLICATION_JSON).content("""
                {"school":{"name":"Wizard Academy","code":"WIZ001","curriculumOptions":["british"],"gradeOptions":[1,2]},
                 "theme":%s,
                 "flags":{"complaints":true,"certificates":false},
                 "manager":{"email":"head@wizard.test","displayName":"Ms Head","password":"wizard-pass-1"}}
                """.formatted(GOOD_THEME)), token)).andExpect(status().isCreated()).andReturn());

        var school = made.get("school");
        assertThat(school.get("name").asText()).isEqualTo("Wizard Academy");
        assertThat(school.get("code").asText()).isEqualTo("WIZ001");
        assertThat(school.get("curriculumOptions").toString()).contains("british");
        assertThat(school.get("gradeOptions").toString()).isEqualTo("[1,2]");
        assertThat(school.get("teachers").asInt()).isZero();

        var user = made.get("user");
        assertThat(made.get("invited").asBoolean()).as("a password was given, so no invitation went out").isFalse();
        assertThat(user.get("email").asText()).isEqualTo("head@wizard.test");
        assertThat(user.get("role").asText()).isEqualTo("MANAGERIAL");
        assertThat(user.get("status").asText()).isEqualTo("active");
        assertThat(user.get("mustChangePassword").asBoolean()).as("§5: the handed-over password is replaced at first sign-in").isTrue();
        assertThat(user.get("schoolName").asText()).isEqualTo("Wizard Academy");

        assertThat(made.get("theme").get("appName").asText()).isEqualTo("Wizard School");
        assertThat(made.get("flags").get(FlagKeys.COMPLAINTS).asBoolean()).isTrue();
        assertThat(made.get("flags").get(FlagKeys.CERTIFICATES).asBoolean()).isFalse();

        // and it is all really there, through the endpoints the dashboard will read it back with
        String id = school.get("id").asText();
        assertThat(json(mvc.perform(get("/schools/" + id + "/theme")).andExpect(status().isOk()).andReturn())
                .get("logoUrl").asText()).isEqualTo("https://cdn.example.test/wizard.png");
        assertThat(json(mvc.perform(get("/schools/" + id + "/flags")).andExpect(status().isOk()).andReturn())
                .get(FlagKeys.COMPLAINTS).asBoolean()).isTrue();

        // and the new manager can sign in with the password she was handed
        var session = json(mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"head@wizard.test\",\"password\":\"wizard-pass-1\"}")).andExpect(status().isOk()).andReturn());
        assertThat(session.get("schoolId").asText()).isEqualTo(id);
        assertThat(session.get("mustChangePassword").asBoolean()).isTrue();
    }

    @Test void without_a_password_the_manager_is_invited_instead() throws Exception {
        var made = json(mvc.perform(admin(post("/admin/schools/wizard").contentType(MediaType.APPLICATION_JSON).content("""
                {"school":{"name":"Invited Academy","code":"WIZ002"},
                 "manager":{"email":"head@invited.test","displayName":"Ms Post"}}
                """), adminToken())).andExpect(status().isCreated()).andReturn());

        assertThat(made.get("invited").asBoolean()).isTrue();
        assertThat(made.get("user").get("status").asText()).isEqualTo("invited");
        assertThat(made.get("user").get("displayName").asText()).isEqualTo("Ms Post");
        assertThat(made.get("theme").isNull()).as("no theme was asked for, so the school keeps the platform default").isTrue();
        assertThat(made.get("flags").get(FlagKeys.CERTIFICATES).asBoolean()).as("the seeded defaults").isTrue();
    }

    // ---------------------------------------------------------------- rollback

    @Test void a_theme_below_the_contrast_rule_leaves_no_school_behind() throws Exception {
        var before = schools.count();
        var error = json(mvc.perform(admin(post("/admin/schools/wizard").contentType(MediaType.APPLICATION_JSON).content("""
                {"school":{"name":"Rolled Back","code":"WIZ003"},
                 "theme":%s,
                 "manager":{"email":"head@rolled.test","password":"rolled-pass-1"}}
                """.formatted(BAD_THEME)), adminToken())).andExpect(status().isBadRequest()).andReturn());

        assertThat(error.get("message").asText()).contains("primaryInk on primary");
        assertNothingWasMade(before, "WIZ003", "head@rolled.test");
    }

    @Test void an_unknown_flag_key_leaves_no_school_behind() throws Exception {
        var before = schools.count();
        mvc.perform(admin(post("/admin/schools/wizard").contentType(MediaType.APPLICATION_JSON).content("""
                {"school":{"name":"Bad Flags","code":"WIZ004"},
                 "flags":{"complaints":true,"teleportation":true},
                 "manager":{"email":"head@badflags.test","password":"badflags-pass-1"}}
                """), adminToken())).andExpect(status().isNotFound());
        assertNothingWasMade(before, "WIZ004", "head@badflags.test");
    }

    @Test void an_address_that_already_has_an_account_leaves_no_school_behind() throws Exception {
        var token = adminToken();
        mvc.perform(admin(post("/admin/schools/wizard").contentType(MediaType.APPLICATION_JSON).content("""
                {"school":{"name":"First Take","code":"WIZ005"},
                 "manager":{"email":"taken@wizard.test","password":"taken-pass-11"}}
                """), token)).andExpect(status().isCreated());

        var before = schools.count();
        mvc.perform(admin(post("/admin/schools/wizard").contentType(MediaType.APPLICATION_JSON).content("""
                {"school":{"name":"Second Take","code":"WIZ006"},
                 "manager":{"email":"taken@wizard.test","password":"taken-pass-22"}}
                """), token)).andExpect(status().isConflict());
        assertThat(schools.count()).isEqualTo(before);
        assertThat(schools.findByCodeIgnoreCase("WIZ006")).isEmpty();
    }

    @Test void a_taken_school_code_leaves_no_user_behind() throws Exception {
        var token = adminToken();
        mvc.perform(admin(post("/admin/schools/wizard").contentType(MediaType.APPLICATION_JSON).content("""
                {"school":{"name":"Code Owner","code":"WIZ007"},
                 "manager":{"email":"owner@wizard.test","password":"owner-pass-11"}}
                """), token)).andExpect(status().isCreated());

        var before = schools.count();
        mvc.perform(admin(post("/admin/schools/wizard").contentType(MediaType.APPLICATION_JSON).content("""
                {"school":{"name":"Code Thief","code":"WIZ007"},
                 "manager":{"email":"thief@wizard.test","password":"thief-pass-11"}}
                """), token)).andExpect(status().isBadRequest());
        assertThat(schools.count()).isEqualTo(before);
        assertThat(users.findByEmailIgnoreCase("thief@wizard.test")).isEmpty();
    }

    @Test void a_weak_password_is_refused_before_anything_is_written() throws Exception {
        var before = schools.count();
        mvc.perform(admin(post("/admin/schools/wizard").contentType(MediaType.APPLICATION_JSON).content("""
                {"school":{"name":"Weak Pass","code":"WIZ008"},
                 "manager":{"email":"head@weak.test","password":"short"}}
                """), adminToken())).andExpect(status().isBadRequest());
        assertNothingWasMade(before, "WIZ008", "head@weak.test");
    }

    @Test void only_an_admin_may_run_the_wizard() throws Exception {
        mvc.perform(post("/admin/schools/wizard").contentType(MediaType.APPLICATION_JSON).content("""
                {"school":{"name":"Nope"},"manager":{"email":"head@nope.test"}}
                """)).andExpect(status().isUnauthorized());
    }

    @Test void the_school_and_the_manager_are_both_required() throws Exception {
        var token = adminToken();
        mvc.perform(admin(post("/admin/schools/wizard").contentType(MediaType.APPLICATION_JSON)
                .content("{\"school\":{\"name\":\"No Manager\"}}"), token)).andExpect(status().isBadRequest());
        mvc.perform(admin(post("/admin/schools/wizard").contentType(MediaType.APPLICATION_JSON)
                .content("{\"manager\":{\"email\":\"head@noschool.test\"}}"), token)).andExpect(status().isBadRequest());
    }

    private void assertNothingWasMade(long schoolsBefore, String code, String email) {
        assertThat(schools.count()).as("the whole wizard is one transaction").isEqualTo(schoolsBefore);
        assertThat(schools.findByCodeIgnoreCase(code)).isEmpty();
        assertThat(users.findByEmailIgnoreCase(email)).isEmpty();
    }
}
