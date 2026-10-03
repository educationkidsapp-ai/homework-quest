package quest.server.chat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import quest.server.files.AttachmentService;

/**
 * B5 review: the two chat upload routes refuse a body larger than a chat file can be <em>before</em> it is parsed.
 * The application's multipart limits are the lesson pipeline's (25 MB a file, 120 MB a request), and the container
 * spools a multipart body — to `/tmp`, which is memory on Cloud Run — as soon as anybody asks for a part, so the
 * 10 MB check in {@link AttachmentService} would come after a 120 MB body had already been held. This filter runs
 * first of all and reads only the `Content-Length` header: over {@link #MAX_BODY} is `413 too_large`, and a body that
 * does not say its length is `411`, because it could not be bounded without being read. Every client sends one for
 * a file it holds in memory (a browser `FormData`, Ktor's `MultiPartFormDataContent` of byte arrays).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ChatUploadLimit extends OncePerRequestFilter {
    /** The largest file a chat takes (a PDF) and room for the multipart envelope around it. */
    static final long MAX_BODY = AttachmentService.MAX_PDF_BYTES + 512 * 1024;
    private static final Pattern PARENT_ROUTE = Pattern.compile("^/children/[^/]+/chat/attachments$");

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return true;
        String path = request.getRequestURI();
        return !"/media/chat-attachments".equals(path) && !PARENT_ROUTE.matcher(path).matches();
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (length < 0) { refuse(response, HttpStatus.LENGTH_REQUIRED, "length_required", "Send the file with a Content-Length."); return; }
        if (length > MAX_BODY) { refuse(response, HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "A PDF must be under 10 MB and an image under 5 MB."); return; }
        chain.doFilter(request, response);
    }

    private static void refuse(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
