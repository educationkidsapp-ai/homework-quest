package quest.server.documents;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The Java mirror of `quest.api.dto.DocumentSummary.kt` (feature 26), records rather than the Kotlin types for
 * {@code VoiceDraftDto}'s reason: springdoc derives a real schema from a record and `server/openapi.json` is what the
 * Angular client is generated from.
 */
public final class DocumentDto {
    private DocumentDto() {}

    /** One date the circular names: an ISO day that really parses, and what happens on it in both languages. */
    @Schema(name = "DocumentKeyDate")
    public record KeyDate(String date, String labelAr, String labelEn) {}

    /**
     * The announcement the assistant suggests, absent when the circular is internal to the staff. It is a draft for
     * the compose sheet: the audience and the expiry are the leader's to choose there, and nothing is sent until she
     * presses Send on `POST /management/broadcasts`.
     */
    @Schema(name = "DocumentAnnouncementDraft")
    public record AnnouncementDraft(String title, String bodyAr, String bodyEn) {}

    /** One circular, read. There is no link to the file: it was never stored. */
    @Schema(name = "DocumentSummary")
    public record View(String id, String fileName, int pages, String title, String summaryAr, String summaryEn,
                       List<KeyDate> keyDates, List<String> actionsAr, List<String> actionsEn,
                       AnnouncementDraft announcement, long createdAt) {}

    /**
     * The assistant's half of a {@link View}, which is what one `summary_json` column holds. Not part of the
     * document: the four columns beside it — the id, the file name, the page count and the hour — are the row's own,
     * and writing them twice is how they come to disagree.
     */
    public record Body(String title, String summaryAr, String summaryEn, List<KeyDate> keyDates,
                       List<String> actionsAr, List<String> actionsEn, AnnouncementDraft announcement) {}
}
