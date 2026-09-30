package quest.server.auth;

import java.util.Optional;

/**
 * MA1: the one door to <strong>where a parent's login actually lives</strong>, which is not this database.
 *
 * <p>A parent signs in to the app with a Firebase email/password account; {@link FirebaseTokenFilter} verifies her ID
 * token and the `parents` row is only the server's local copy of a uid, an address, a telephone number and (V25) a
 * name. So `POST /admin/children`, which is the Admin typing a family into the room, has to create the account in
 * Firebase before it can write the row — and a test, the H2 profile and the seed must be able to do the same thing
 * without a Firebase project, a network call or a service-account key.
 *
 * <p>Hence a port with two implementations, chosen in {@link ParentAccountsConfig} by the one setting that already
 * decides whether parent tokens are real: `quest.auth.fake`. Nothing above this interface knows which is in use.
 */
public interface ParentAccounts {
    /** A parent's login as the provider holds it. `uid` is what a `parents` row stores in `firebase_uid`. */
    record Account(String uid, String email) {}

    /** The account for this address, or empty when the provider has none. */
    Optional<Account> byEmail(String email);

    /** Creates one. The address is already taken is a {@code conflict}; the password has already been validated. */
    Account create(String email, String password, String displayName);

    /** Replaces the password of an account that exists, which signs her other devices out on their next refresh. */
    void password(String uid, String password);
}
