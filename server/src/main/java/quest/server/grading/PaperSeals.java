package quest.server.grading;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import quest.server.config.QuestProperties;

/**
 * B3: the key {@link PaperSeal}s are made with, from its own secret `EXAM_PAPER_SECRET` (`quest.exams.paper-secret`)
 * so that rotating the JWT signing key can never change the opaque ids of a paper a child downloaded earlier — the
 * grading of what she sent depends on them.
 *
 * <p>Missing, it is <strong>fatal in `prod`</strong>; in `qa` it is logged as an error and a key is derived from the
 * JWT secret under its own label so grading still works; anywhere else (a laptop, `h2`, the tests) a fixed
 * development key is used. The value is never logged.
 */
@Component
public class PaperSeals {
    private static final Logger log = LoggerFactory.getLogger(PaperSeals.class);
    private final byte[] key;

    public PaperSeals(@Value("${quest.exams.paper-secret:}") String secret, QuestProperties props, Environment env) {
        var profiles = Arrays.asList(env.getActiveProfiles());
        String material;
        if (secret != null && !secret.isBlank()) material = "exam-paper|" + secret;
        else if (profiles.contains("prod")) throw new IllegalStateException("EXAM_PAPER_SECRET is not set: sealed exam papers cannot be keyed.");
        else if (profiles.contains("qa")) {
            log.error("EXAM_PAPER_SECRET is not set: sealed exam paper ids are keyed from the JWT secret until it is.");
            material = "sealed-paper|" + (props.auth() == null ? "" : props.auth().jwtSecret());
        } else material = "homework-quest-dev-exam-paper";
        try { key = java.security.MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public PaperSeal of(String parentId, String lessonId) { return new PaperSeal(key, parentId, lessonId); }
}
