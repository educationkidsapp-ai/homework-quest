package quest.server.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import quest.server.config.QuestProperties;

/**
 * Verifies the parent's Firebase ID token on every `/children/**` and `/lessons/**` request and maps it to a
 * `parents` row (created on first sight). With `quest.auth.fake=true` (development, no Firebase project yet)
 * a `Bearer fake-token-<uid>` header is accepted instead.
 */
@Component
public class FirebaseTokenFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(FirebaseTokenFilter.class);
    private final QuestProperties props;
    private final ParentRepository parents;
    private FirebaseAuth firebase;

    public FirebaseTokenFilter(QuestProperties props, ParentRepository parents) {
        this.props = props; this.parents = parents;
        if (!props.auth().fake()) {
            try {
                if (FirebaseApp.getApps().isEmpty()) {
                    // FIREBASE_CREDENTIALS is either a path to the service-account file or (Secret Manager) the JSON itself
                    String cfg = props.auth().firebaseCredentials();
                    GoogleCredentials creds = cfg == null || cfg.isBlank() ? GoogleCredentials.getApplicationDefault()
                            : cfg.trim().startsWith("{") ? GoogleCredentials.fromStream(new java.io.ByteArrayInputStream(cfg.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                            : GoogleCredentials.fromStream(new FileInputStream(cfg));
                    FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(creds).build());
                }
                firebase = FirebaseAuth.getInstance();
                log.info("Firebase Admin initialised");
            } catch (Exception e) {
                log.error("Firebase Admin could not be initialised ({}); parent endpoints will reject every token. Set FIREBASE_CREDENTIALS or FAKE_AUTH=true.", e.getMessage());
            }
        } else log.warn("FAKE_AUTH active: accepting 'Bearer fake-token-<uid>'");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && !header.startsWith("Bearer admin.")) {
            String token = header.substring(7).trim();
            Principals.Parent principal = null;
            if (props.auth().fake() && token.startsWith("fake-token-")) {
                String uid = token.substring("fake-token-".length());
                principal = parent(uid, uid + "@fake.local");
            } else if (firebase != null) {
                try { FirebaseToken t = firebase.verifyIdToken(token); principal = parent(t.getUid(), t.getEmail() == null ? "" : t.getEmail()); }
                catch (Exception e) { log.debug("firebase token rejected: {}", e.getMessage()); }
            }
            if (principal != null) {
                var auth = new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_PARENT")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }

    private Principals.Parent parent(String uid, String email) {
        var row = parents.findByFirebaseUid(uid).orElseGet(() -> {
            var p = new Entities.ParentEntity();
            p.setId(UUID.randomUUID().toString()); p.setFirebaseUid(uid); p.setEmail(email); p.setCreatedAt(Instant.now());
            return parents.save(p);
        });
        return new Principals.Parent(row.getId(), uid, row.getEmail());
    }
}
