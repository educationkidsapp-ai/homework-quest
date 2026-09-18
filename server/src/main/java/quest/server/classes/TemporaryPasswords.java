package quest.server.classes;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * The password an Admin reads out when she creates a teacher's account, or resets one
 * (`docs/prompts/dashboard-first-one-school.md` §6). It is answered <strong>once</strong>, in the body of that one
 * request, and nowhere else: it is never written to a log, an audit row or a mail, and only its bcrypt hash reaches
 * the database. The account carries `must_change_password`, so the value stops being a secret the first time its
 * owner signs in.
 *
 * <p>Twelve characters from an alphabet without the look-alikes a person mistypes when reading a password off a
 * screen (`O`/`0`, `I`/`l`/`1`), with one digit and one symbol guaranteed so it also satisfies any policy stricter
 * than the platform's own ten-character minimum.
 */
@Component
public class TemporaryPasswords {
    private static final String LETTERS = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!@#%?*";
    static final int LENGTH = 12;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        var out = new StringBuilder(LENGTH);
        out.append(pick(DIGITS)).append(pick(SYMBOLS));
        while (out.length() < LENGTH) out.append(pick(LETTERS));
        return shuffle(out);
    }

    private char pick(String alphabet) { return alphabet.charAt(random.nextInt(alphabet.length())); }

    private String shuffle(StringBuilder value) {
        for (int i = value.length() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = value.charAt(i); value.setCharAt(i, value.charAt(j)); value.setCharAt(j, tmp);
        }
        return value.toString();
    }
}
