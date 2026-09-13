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

/** Method, path, status and duration only — request bodies (slides) are never logged. */
@Component
public class RequestLogging extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("http");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        try { chain.doFilter(request, response); }
        finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (!request.getRequestURI().startsWith("/actuator")) log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(), response.getStatus(), ms);
        }
    }
}
