package quest.server.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import quest.server.tenancy.TenantInterceptor;

/** CORS for the web admin panel (Firebase Hosting / localhost dev server), and the per-request tenant scope. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final TenantInterceptor tenantInterceptor;
    public WebConfig(TenantInterceptor tenantInterceptor) { this.tenantInterceptor = tenantInterceptor; }

    /** Resolves `X-School-Id` once per request, before any handler or transaction (404 unknown, 403 another school). */
    @Override
    public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(tenantInterceptor); }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(@org.springframework.beans.factory.annotation.Value("${quest.cors-origins:http://localhost:8081,http://localhost:8080}") String origins) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOriginPatterns(List.of(origins.split(",")));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setExposedHeaders(List.of("ETag", "Cache-Control"));
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/**", c);
        return s;
    }
}
