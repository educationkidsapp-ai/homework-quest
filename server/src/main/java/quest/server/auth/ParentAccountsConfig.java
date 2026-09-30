package quest.server.auth;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import quest.server.config.ApiException;
import quest.server.config.QuestProperties;

/**
 * Which {@link ParentAccounts} the server runs with, decided by `quest.auth.fake` — the setting that already decides
 * whether a parent's <em>token</em> is real. `fake=true` is the test profile, the H2 profile and local development, and
 * those must be able to create a family without a Firebase project or a network call; `fake=false` is QA and
 * production, where {@link FirebaseTokenFilter} has initialised the Firebase app from `FIREBASE_CREDENTIALS`.
 *
 * <p><strong>The Firebase app is not resolved here.</strong> {@link Firebase} looks it up on first use, because the
 * only thing that initialises it is {@code FirebaseTokenFilter}'s constructor and a bean method may run before it.
 * An environment with `fake=false` and no usable credentials answers 503 on the one route that needs them rather than
 * refusing to start: token verification is the same app and already degrades that way.
 */
@Configuration
public class ParentAccountsConfig {
    @Bean
    public ParentAccounts parentAccounts(QuestProperties props) {
        return props.auth().fake() ? new Fake() : new Firebase();
    }

    /** Firebase Admin: `getUserByEmail`, `createUser`, `updateUser`. Needs a credential with user-management rights. */
    static final class Firebase implements ParentAccounts {
        @Override public Optional<Account> byEmail(String email) {
            try { return Optional.of(account(auth().getUserByEmail(email))); }
            catch (FirebaseAuthException e) {
                if (com.google.firebase.auth.AuthErrorCode.USER_NOT_FOUND.equals(e.getAuthErrorCode())) return Optional.empty();
                throw failed(e);
            }
        }

        @Override public Account create(String email, String password, String displayName) {
            var request = new UserRecord.CreateRequest().setEmail(email).setPassword(password).setEmailVerified(false);
            if (displayName != null && !displayName.isBlank()) request.setDisplayName(displayName);
            try { return account(auth().createUser(request)); }
            catch (FirebaseAuthException e) {
                if (com.google.firebase.auth.AuthErrorCode.EMAIL_ALREADY_EXISTS.equals(e.getAuthErrorCode()))
                    throw ApiException.conflict("That address already has a parent account.");
                throw failed(e);
            }
        }

        @Override public void password(String uid, String password) {
            try { auth().updateUser(new UserRecord.UpdateRequest(uid).setPassword(password)); }
            catch (FirebaseAuthException e) { throw failed(e); }
        }

        private static FirebaseAuth auth() {
            if (FirebaseApp.getApps().isEmpty())
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable",
                        "Parent accounts are not configured: set FIREBASE_CREDENTIALS or FAKE_AUTH=true.");
            return FirebaseAuth.getInstance();
        }

        /** The provider's message is not the caller's business and may name the project; only the code travels. */
        private static ApiException failed(FirebaseAuthException e) {
            return new ApiException(HttpStatus.BAD_GATEWAY, "upstream_error",
                    "The parent account could not be written (" + e.getAuthErrorCode() + ").");
        }

        private static Account account(UserRecord record) { return new Account(record.getUid(), record.getEmail()); }
    }

    /**
     * The in-memory stand-in for the test and H2 profiles: the same contract, no network. The password is kept only so
     * that a test can assert it changed — nothing reads it to authenticate, because `fake=true` also means
     * {@code Bearer fake-token-<uid>} is what signs a parent in.
     */
    static final class Fake implements ParentAccounts {
        private final Map<String, Account> byEmail = new ConcurrentHashMap<>();
        private final Map<String, String> passwords = new ConcurrentHashMap<>();

        @Override public Optional<Account> byEmail(String email) { return Optional.ofNullable(byEmail.get(key(email))); }

        @Override public Account create(String email, String password, String displayName) {
            var account = new Account("fake-parent-" + UUID.randomUUID().toString().substring(0, 8), email);
            if (byEmail.putIfAbsent(key(email), account) != null) throw ApiException.conflict("That address already has a parent account.");
            passwords.put(account.uid(), password);
            return account;
        }

        @Override public void password(String uid, String password) { passwords.put(uid, password); }

        private static String key(String email) { return email == null ? "" : email.trim().toLowerCase(Locale.ROOT); }
    }
}
