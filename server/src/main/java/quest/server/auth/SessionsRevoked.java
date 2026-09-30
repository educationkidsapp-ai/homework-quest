package quest.server.auth;

/**
 * T1: every refresh token of one user has just been revoked — `POST /auth/sign-out`, a password change, a password
 * reset, or a replayed token that killed the family. Published as a Spring application event rather than called
 * directly so that the auth package keeps knowing nothing about the socket: {@code ChatPresence} listens and closes
 * her `/ws/chat` sessions, which is what makes a signed-out manager stop showing as "Live".
 */
public record SessionsRevoked(String userId) {}
