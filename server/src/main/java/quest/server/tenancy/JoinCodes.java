package quest.server.tenancy;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;
import quest.server.config.ApiException;

/**
 * The six characters a parent types to join a section (`docs/teacher-flow.md` §2). The alphabet leaves out the pairs
 * a person reading a printed card confuses — `O`/`0`, `I`/`1`/`L`, `S`/`5`, `B`/`8` — so a wrong code is a typo the
 * parent made rather than one the card invited.
 */
@Component
public class JoinCodes {
    /** 27 unambiguous characters; 27^6 ≈ 4·10^8 codes, and the uniqueness check below closes the rest. */
    static final String ALPHABET = "ACDEFGHJKMNPQRTUVWXYZ234679";
    public static final int LENGTH = 6;
    private static final int ATTEMPTS = 50;

    private final SecureRandom random = new SecureRandom();
    private final ClassRepository classes;

    public JoinCodes(ClassRepository classes) { this.classes = classes; }

    /** A code no class holds yet. Collisions are retried rather than allowed to surface as a unique-index violation. */
    public String generate() {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            String code = raw();
            if (!classes.existsByJoinCodeIgnoreCase(code)) return code;
        }
        throw new IllegalStateException("could not find a free join code in " + ATTEMPTS + " attempts");
    }

    private String raw() {
        var out = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return out.toString();
    }

    /** What a parent typed, tidied: spaces and dashes out, upper case. 400 when it is not six characters. */
    public static String normalise(String code) {
        String cleaned = code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
        if (cleaned.length() != LENGTH) throw ApiException.badRequest("A join code is " + LENGTH + " characters.");
        return cleaned;
    }
}
