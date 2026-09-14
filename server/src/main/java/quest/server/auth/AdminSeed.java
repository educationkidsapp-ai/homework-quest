package quest.server.auth;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import quest.server.config.QuestProperties;

/** Creates the first admin from ADMIN_EMAIL / ADMIN_PASSWORD (the "CLI seed script": `ADMIN_EMAIL=… ADMIN_PASSWORD=… ./mvnw spring-boot:run`). */
@Component
public class AdminSeed implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminSeed.class);
    private final AdminUserRepository admins; private final PasswordEncoder encoder; private final QuestProperties props;
    public AdminSeed(AdminUserRepository admins, PasswordEncoder encoder, QuestProperties props) { this.admins = admins; this.encoder = encoder; this.props = props; }

    @Override public void run(String... args) {
        String email = props.admin().seedEmail(); String password = props.admin().seedPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) return;
        var existing = admins.findByEmailIgnoreCase(email);
        var a = existing.orElseGet(() -> { var n = new Entities.AdminUserEntity(); n.setId(UUID.randomUUID().toString()); n.setEmail(email.trim()); n.setCreatedAt(Instant.now()); return n; });
        a.setPasswordHash(encoder.encode(password));
        admins.save(a);
        log.info("admin user ready: {}", email);
    }
}
