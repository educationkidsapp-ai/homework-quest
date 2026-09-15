package quest.server.auth;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import quest.server.config.QuestProperties;

/** Creates the platform ADMIN from ADMIN_EMAIL / ADMIN_PASSWORD (the "CLI seed script": `ADMIN_EMAIL=… ADMIN_PASSWORD=… ./mvnw spring-boot:run`). */
@Component
public class AdminSeed implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminSeed.class);
    private final UserRepository users; private final PasswordEncoder encoder; private final QuestProperties props;
    public AdminSeed(UserRepository users, PasswordEncoder encoder, QuestProperties props) { this.users = users; this.encoder = encoder; this.props = props; }

    @Override public void run(String... args) {
        String email = props.admin().seedEmail(); String password = props.admin().seedPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) return;
        var normalised = email.trim().toLowerCase();
        var u = users.findByEmailIgnoreCase(normalised).orElseGet(() -> {
            var n = new Entities.UserEntity();
            n.setId(UUID.randomUUID().toString()); n.setEmail(normalised); n.setCreatedAt(Instant.now());
            return n;
        });
        u.setSchoolId(null); u.setRole("ADMIN"); u.setStatus("active"); u.setMustChangePassword(false);
        u.setPasswordHash(encoder.encode(password)); u.setUpdatedAt(Instant.now());
        users.save(u);
        log.info("admin user ready: {}", normalised);
    }
}
