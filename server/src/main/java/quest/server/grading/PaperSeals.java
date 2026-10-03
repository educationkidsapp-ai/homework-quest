package quest.server.grading;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import quest.server.config.QuestProperties;

/**
 * B3: the key {@link PaperSeal}s are made with — derived from the server's JWT secret under its own label, so it is
 * never the signing key itself and never leaves the server. A development run with no secret configured falls back
 * to a fixed one, which is fine for a database nobody else can read.
 */
@Component
public class PaperSeals {
    private final byte[] key;

    public PaperSeals(QuestProperties props) {
        String secret = props.auth() == null || props.auth().jwtSecret() == null ? "homework-quest-dev" : props.auth().jwtSecret();
        try { key = java.security.MessageDigest.getInstance("SHA-256").digest(("sealed-paper|" + secret).getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public PaperSeal of(String parentId, String lessonId) { return new PaperSeal(key, parentId, lessonId); }
}
