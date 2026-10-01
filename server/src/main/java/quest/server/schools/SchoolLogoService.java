package quest.server.schools;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import quest.server.auth.AuditService;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.files.AttachmentService;
import quest.server.files.FileStore;
import quest.server.platform.ThemeService;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;

/**
 * S1: a school's logo as an upload. The bytes live in the {@link FileStore} under `school-logos/{schoolId}/…` and the
 * school row names them (V27); the URL every client reads is derived by {@link ThemeService#themeOf}, so the logo
 * reaches the sign-in page, the app's theme and `/me/home` through the one field they already read.
 *
 * <p>The school is the path's, never a tenant header's, and only `school.write` (ADMIN) reaches the two writes. The
 * read is public and can only ever answer one of three sniffed image types for the id it was asked about.
 */
@Service
public class SchoolLogoService {
    /** 1 MB: a logo is drawn at a few dozen pixels on the sign-in page. */
    public static final long MAX_BYTES = 1024 * 1024;

    private final SchoolRepository schools; private final FileStore files; private final ThemeService themes;
    private final AuditService audit; private final Clock clock;

    public SchoolLogoService(SchoolRepository schools, FileStore files, ThemeService themes, AuditService audit, Clock clock) {
        this.schools = schools; this.files = files; this.themes = themes; this.audit = audit; this.clock = clock;
    }

    @Transactional
    public SchoolDto.SchoolLogo upload(Principals.User actor, String schoolId, MultipartFile file) {
        var school = require(schoolId);
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Send the image as `file`.");
        if (file.getSize() > MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "A logo must be under 1 MB.");
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (java.io.IOException e) { throw ApiException.badRequest("That file could not be read."); }
        String mime = AttachmentService.imageType(bytes);
        if (mime == null) throw ApiException.badRequest("A logo is a JPEG, PNG or WebP image.");
        String previous = school.getLogoPath();
        var stored = files.put("school-logos/" + school.getId() + "/" + UUID.randomUUID(), bytes, mime);
        school.setLogoPath(stored.path()); school.setLogoType(mime); school.setLogoUpdatedAt(clock.instant());
        schools.save(school);
        if (previous != null) files.delete(previous);
        audit.record(actor.userId(), "school.logo", "school", school.getId(), school.getId(), Map.of("type", mime));
        return new SchoolDto.SchoolLogo(school.getName(), themes.uploadedLogoUrl(school));
    }

    @Transactional
    public void delete(Principals.User actor, String schoolId) {
        var school = require(schoolId);
        String previous = school.getLogoPath();
        school.setLogoPath(null); school.setLogoType(null); school.setLogoUpdatedAt(null);
        themes.clearTypedLogo(school);
        schools.save(school);
        if (previous != null) files.delete(previous);
        audit.record(actor.userId(), "school.logo", "school", school.getId(), school.getId(), Map.of("type", ""));
    }

    /** The bytes and their stored type; 404 for an unknown school and for one with no upload, alike. */
    @Transactional(readOnly = true)
    public FileStore.Blob read(String schoolId) {
        var school = schools.findById(schoolId == null ? "" : schoolId).filter(s -> s.getLogoPath() != null)
                .orElseThrow(() -> ApiException.notFound("logo"));
        var blob = files.get(school.getLogoPath()).orElseThrow(() -> ApiException.notFound("logo"));
        return new FileStore.Blob(blob.bytes(), school.getLogoType());
    }

    private SchoolEntity require(String schoolId) {
        return schools.findById(schoolId == null ? "" : schoolId).orElseThrow(() -> ApiException.notFound("school"));
    }
}
