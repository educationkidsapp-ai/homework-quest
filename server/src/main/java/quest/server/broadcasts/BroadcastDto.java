package quest.server.broadcasts;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The Java mirror of `quest.api.dto.Broadcasts.kt` (RM2, DR6). Records rather than the Kotlin types, for
 * {@link quest.server.teacher.TeacherDto}'s reason: springdoc derives a real schema from a record and
 * `server/openapi.json` is what the Angular client is generated from. The enums are strings here and enums on the
 * wire's other side — `kind`, `audience` and `curriculum` carry exactly the words the contract serialises.
 */
public final class BroadcastDto {
    private BroadcastDto() {}

    /**
     * What is attached. MH1 turns RM2's free text into a reference: `id` is an `attachments` row uploaded through
     * `POST /media/attachments`, and `url` is then `/media/attachments/{id}` so a client that only knows how to render
     * a URL needs no change. A row written before MH1 has `url` (whatever the composer typed) and no `id`.
     */
    @Schema(name = "BroadcastAttachment")
    public record Attachment(@Size(max = 500) String url, @Size(max = 200) String name, String id, String type) {}

    /**
     * `POST /management/broadcasts` and `POST /coordinator/broadcasts`. An empty `sectionIds` is the author's whole
     * scope; `grade` (MG1) narrows it to one grade of the department and may not be sent with `sectionIds`.
     *
     * <p>MH1: a `weekly_plan` <strong>requires</strong> `grade` and `attachmentId` and nothing else — the owner's plan
     * is "the week and an uploaded image", so `bodyEn` and `title` are optional there and the server writes both. On
     * the other two kinds `bodyEn` is still required; the validation is in {@link BroadcastService} because it depends
     * on the kind, which bean validation cannot see.
     */
    @Schema(name = "CreateBroadcastRequest")
    public record CreateRequest(@NotBlank String kind, @Size(max = 1000) String bodyEn,
                                @Size(max = 120) String title, @Size(max = 1000) String bodyAr, String weekStart,
                                Integer grade, List<String> audience, List<String> sectionIds, Attachment attachment,
                                @Size(max = 64) String attachmentId, Long expiresAt) {}

    /** One row of a feed or of a composer's list; `read` is the caller's own flag, never another recipient's. */
    @Schema(name = "BroadcastView")
    public record View(String id, String kind, String authorId, String authorName, String authorRole, String title,
                       String bodyEn, String bodyAr, String weekStart, String curriculum, Integer grade, String subject,
                       List<String> sectionIds, List<String> audience, Attachment attachment, Long expiresAt,
                       long createdAt, boolean read) {}

    /** `GET /me/broadcasts` and `GET /children/{id}/broadcasts`: newest first, with the caller's own unread count. */
    @Schema(name = "BroadcastFeed")
    public record Feed(int unread, List<View> items) {}

    // ---------------------------------------------------------------- MG1: the weekly-plan archive

    /** One plan of the archive. `readBy` is answered on the manager's own archive only; see {@link BroadcastService}. */
    @Schema(name = "WeeklyPlanEntry")
    public record PlanEntry(View plan, Integer readBy) {}

    /** One school week, its plans all-grades first then by grade. */
    @Schema(name = "WeeklyPlanWeek")
    public record PlanWeek(String weekStart, List<PlanEntry> items) {}

    /**
     * The archive over a window, newest week first, past weeks included — a feed may hide them and this never does.
     * `unread` (MH1) is the caller's own count inside the window: a parent has no bell, so on
     * `GET /children/{id}/weekly-plans` this number <em>is</em> her notification that a plan has arrived.
     */
    @Schema(name = "WeeklyPlanArchive")
    public record PlanArchive(String from, String to, int unread, List<PlanWeek> weeks) {}
}
