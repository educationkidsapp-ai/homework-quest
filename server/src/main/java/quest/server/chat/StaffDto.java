package quest.server.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * T1 (owner's list, 2026-10-01): the staff directory's wire shapes. Plain records rather than the kotlinx codec, for
 * the reason `CoordinatorDto.CoordinatorManager` — which this replaces — gave: there is no `Stop` in them and springdoc
 * needs a real schema for the generated dashboard client.
 */
public final class StaffDto {
    private StaffDto() {}

    /**
     * A job title in parts, so the dashboard writes it in the reader's own language instead of translating an English
     * sentence. `kind` is `coordinator` or `manager`; `grades` are the caller's own grades this coordinator covers,
     * ascending, and empty on a manager, whose department is the whole of her job.
     */
    @Schema(name = "StaffJobParts")
    public record StaffJobParts(String kind, List<Integer> grades, String subject, String curriculum) {}

    /**
     * One person in the directory — `GET /teacher/coordinators`, `GET /teacher/managers` and `GET /coordinator/managers`
     * all answer this, because they are one screen asked from two roles. `userId`, `displayName` and `curriculum` are
     * the fields RM1's chooser already carried, so a client written against it reads this row unchanged; `job` is the
     * English sentence to fall back on and `jobParts` the same thing localisable — **a list**, one entry per track
     * (review), and `job` joins its sentences with "; ". `curriculum` is the first of those tracks and `subjects` the
     * union across them, both for the RM1 client that reads one word. `online` is presence (T1).
     */
    /**
     * T1b `POST /teacher/chat/staff-threads`: whom the teacher wants to talk to — **exactly one** of the two ids, and
     * 400 for both or neither, because a manager and a coordinator are different people and a request naming both is
     * asking for a thread that does not exist. Each is validated against the directory page that offered it
     * (`GET /teacher/managers`, `GET /teacher/coordinators`), so anyone outside her own reach is 404.
     */
    @Schema(name = "OpenStaffThreadRequest")
    public record OpenStaffThreadRequest(String managerUserId, String coordinatorUserId) {}

    @Schema(name = "StaffContact")
    public record StaffContact(String userId, String displayName, String email, String role, String job,
                               List<StaffJobParts> jobParts, String phone, String curriculum, String subjects, boolean online) {}
}
