package quest.server.broadcasts;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The Java mirror of `quest.api.dto.VoiceDraft.kt` (feature 22), records rather than the Kotlin types for
 * {@link BroadcastDto}'s reason: springdoc derives a real schema from a record and `server/openapi.json` is what the
 * Angular client is generated from. `curriculum` is a string here and a `Curriculum` on the wire's other side,
 * carrying exactly the word the contract serialises.
 */
public final class VoiceDraftDto {
    private VoiceDraftDto() {}

    /**
     * `POST /management/broadcasts/voice-drafts`. The transcript her device produced — the server never receives
     * audio. {@code @NotBlank} and the length are both 400s; {@link VoiceDraftService} repeats the length check so the
     * message says what the limit is.
     */
    @Schema(name = "CreateVoiceDraftRequest")
    public record CreateRequest(@NotBlank @Size(max = VoiceDraftService.MAX_TRANSCRIPT) String transcript) {}

    /** The suggested audience; absent, or either field null, means the author's whole scope. */
    @Schema(name = "VoiceDraftAudience")
    public record Audience(String curriculum, Integer grade) {}

    /** One prepared announcement, stored unsent, with the transcript the preview shows underneath it. */
    @Schema(name = "VoiceDraft")
    public record View(String id, String title, String bodyAr, String bodyEn, Audience audience, Long expiresAt,
                       String transcript) {}
}
