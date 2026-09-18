package quest.server.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Two token filters and one matrix. The URL matchers only say who may knock; what each endpoint actually needs is
 * the `@PreAuthorize("@permit.has('…')")` on it, read from `permissions.json` (§5).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, FirebaseTokenFilter firebase, AdminJwtFilter adminJwt, ReadOnlyGuard readOnly) throws Exception {
        http.csrf(c -> c.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/health", "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/panel", "/panel/**", "/dashboard", "/dashboard/**").permitAll()
                .requestMatchers("/admin/auth/sign-in", "/auth/**", "/invites/**", "/schools/by-code/**").permitAll()
                // §3/§4/§A: the app and the sign-in page read these before anyone has a token. Listed one by one so
                // a later `/schools/**` route is authenticated until it is deliberately opened here.
                .requestMatchers("/platform-settings", "/schools/*/flags", "/schools/*/theme", "/schools/logo").permitAll()
                // V7: the class join code, the narrower sibling of `/schools/by-code/**`. Exactly this path, so a
                // later `/classes/**` route is authenticated until it is deliberately opened here.
                .requestMatchers("/classes/lookup").permitAll()
                .requestMatchers("/me", "/me/**").hasAnyRole("ADMIN", "TEACHER", "MANAGERIAL")
                // §6 screens 19–20: "my own school", with no school id in the path. Dashboard roles only; which of
                // them may read what is the `@PreAuthorize` on each route, as everywhere else.
                .requestMatchers("/school/**").hasAnyRole("ADMIN", "TEACHER", "MANAGERIAL")
                // §6 screens 11-16: the same shape, for the teacher's own profile, classes, students and questions.
                .requestMatchers("/teacher/**").hasAnyRole("ADMIN", "TEACHER", "MANAGERIAL")
                .requestMatchers("/admin/**").hasAnyRole("ADMIN", "TEACHER", "MANAGERIAL")
                .requestMatchers("/children/**", "/lessons/**").hasRole("PARENT")
                .requestMatchers("/media/**").hasAnyRole("PARENT", "ADMIN", "TEACHER", "MANAGERIAL")
                .anyRequest().authenticated())
            .addFilterBefore(firebase, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(adminJwt, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(readOnly, AdminJwtFilter.class);
        return http.build();
    }

    /**
     * BCrypt at strength 12 (~250 ms per hash on the Cloud Run instance, four times the Spring default of 10). Only
     * new hashes are written at 12: `BCryptPasswordEncoder` reads the cost out of each stored hash, so every password
     * set before this still verifies, and `AdminSeed` re-encodes the seeded admin on every start as it always has.
     */
    @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
}
