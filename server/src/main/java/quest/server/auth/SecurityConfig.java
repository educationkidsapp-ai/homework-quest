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
                .requestMatchers("/health", "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/media/**", "/panel", "/panel/**").permitAll()
                .requestMatchers("/admin/auth/sign-in", "/auth/**", "/invites/**", "/schools/by-code/**").permitAll()
                .requestMatchers("/me", "/me/**").hasAnyRole("ADMIN", "TEACHER", "MANAGERIAL")
                .requestMatchers("/admin/**").hasAnyRole("ADMIN", "TEACHER", "MANAGERIAL")
                .requestMatchers("/children/**", "/lessons/**").hasRole("PARENT")
                .anyRequest().authenticated())
            .addFilterBefore(firebase, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(adminJwt, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(readOnly, AdminJwtFilter.class);
        return http.build();
    }

    @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
}
