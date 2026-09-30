package quest.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.firebase.FirebaseApp;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import quest.server.config.ApiException;
import quest.server.config.QuestProperties;

/**
 * MA1's {@link ParentAccounts} port, both halves, without a Spring context and without a Firebase project.
 *
 * <p>The half that needed a test is the <strong>503</strong>: the PR body and `docs/runbook.md` tell QA that a
 * deployment with `quest.auth.fake=false` and no usable Firebase app answers
 * *"Parent accounts are not configured"* on the two parent-account routes and degrades nowhere else — and nothing
 * asserted it. It is assertable exactly as it happens in production, because what {@code Firebase.auth()} checks is
 * {@link FirebaseApp#getApps()}, and in this JVM that list is empty: the suite runs `quest.auth.fake=true`, so
 * {@code FirebaseTokenFilter} — the only thing that ever initialises the app — never does.
 *
 * <p>If a future test ever initialises a Firebase app in this JVM, {@link #the_firebase_half_answers_503_without_a_project}
 * stops proving anything and starts failing with the provider's own error instead; the guard on the first line says so
 * rather than letting it pass quietly.
 */
class ParentAccountsTest {
    private final ParentAccountsConfig config = new ParentAccountsConfig();

    @Test void the_firebase_half_answers_503_without_a_project() {
        assertThat(FirebaseApp.getApps())
                .as("this JVM runs quest.auth.fake=true, so no Firebase app is ever initialised").isEmpty();
        var firebase = new ParentAccountsConfig.Firebase();

        // All three calls, because all three are reachable from a route: two from `POST /admin/children` and one from
        // `POST /admin/children/{id}/parent/reset-password`.
        for (var call : java.util.List.<org.junit.jupiter.api.function.Executable>of(
                () -> firebase.byEmail("nobody@qa.test"),
                () -> firebase.create("nobody@qa.test", "read-it-out", "Nobody"),
                () -> firebase.password("some-uid", "read-it-out")))
            assertThatThrownBy(call::execute)
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Parent accounts are not configured")
                    .extracting(e -> ((ApiException) e).status(), e -> ((ApiException) e).error().code())
                    .containsExactly(HttpStatus.SERVICE_UNAVAILABLE, "unavailable");
    }

    @Test void the_bean_is_the_stand_in_under_fake_auth_and_firebase_otherwise() {
        assertThat(config.parentAccounts(properties(true))).isInstanceOf(ParentAccountsConfig.Fake.class);
        assertThat(config.parentAccounts(properties(false))).isInstanceOf(ParentAccountsConfig.Firebase.class);
    }

    /** The stand-in's contract, which is the one the whole suite and the seed run against. */
    @Test void the_stand_in_creates_finds_refuses_a_repeat_and_changes_a_password() {
        var fake = new ParentAccountsConfig.Fake();
        assertThat(fake.byEmail("parent@qa.test")).isEmpty();
        var account = fake.create("Parent@QA.test", "read-it-out", "A Parent");
        assertThat(account.uid()).startsWith("fake-parent-");
        // Found case-insensitively, as an address is everywhere else in this codebase.
        assertThat(fake.byEmail("parent@qa.test")).contains(account);
        assertThatThrownBy(() -> fake.create("parent@qa.test", "read-it-out", "A Parent"))
                .isInstanceOf(ApiException.class).hasMessageContaining("already has a parent account");
        fake.password(account.uid(), "a-new-one");                            // the reset path, and it does not throw
    }

    /** Only `auth().fake()` is read; the rest of the tree is null, because a record cannot be mocked (it is final). */
    private static QuestProperties properties(boolean fake) {
        var auth = new QuestProperties.Auth(fake, null, "x".repeat(48), 1, 15, 30, 30, 7, 30, 10, 15);
        return new QuestProperties(auth, null, null, null, null, null, null, null, null, null, null, null);
    }
}
