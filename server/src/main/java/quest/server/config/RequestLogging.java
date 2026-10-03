package quest.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Method, path, status, duration — never request bodies (slides, FCM tokens) or query strings. A route must therefore
 * never carry a secret in its path: B4's sign-out is `POST /me/devices/unregister` with the token in the body for that
 * reason.
 */
@Component
public class RequestLogging extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("http");
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        try { chain.doFilter(req, res); }
        finally { if (!req.getRequestURI().startsWith("/actuator")) log.info("{} {} -> {} ({} ms)", req.getMethod(), req.getRequestURI(), res.getStatus(), (System.nanoTime() - start) / 1_000_000); }
    }
}
