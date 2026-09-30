package quest.server.files;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import quest.server.auth.Principals;
import quest.server.children.ChildService;
import quest.server.children.Entities.ChildEntity;
import quest.server.children.ChildRepository;
import quest.server.config.ApiException;
import quest.server.content.LessonRepository;

/**
 * Who may read an id-addressed media file (§2). The ids are guessable — `StopIds.pageImageId` builds a page crop id
 * as `<first 8 of the lesson id>:page-N` — so the route cannot rely on the id being a secret: every request is
 * resolved back to a lesson or a child and checked against the caller.
 *
 * <p>Everything a caller may not read is <strong>404</strong>, never 403: a refusal that distinguished "exists but is
 * not yours" from "does not exist" would confirm another school's ids to anyone with a token.
 */
@Service
public class MediaAccess {
    private final LessonRepository lessons; private final ChildRepository children; private final ChildService childService;
    private final quest.server.broadcasts.BroadcastService broadcasts;

    public MediaAccess(LessonRepository lessons, ChildRepository children, ChildService childService,
                       quest.server.broadcasts.BroadcastService broadcasts) {
        this.lessons = lessons; this.children = children; this.childService = childService; this.broadcasts = broadcasts;
    }

    /**
     * `/media/pages/{id}`, by the lesson the crop belongs to.
     *
     * <ul>
     *   <li>A dashboard user reads the lessons in her scope, published or not (the review screen shows the crops
     *       before publication). The lookup is the tenant-scoped {@link LessonRepository#findOneById} — a TEACHER or
     *       MANAGERIAL token, and an ADMIN with `X-School-Id`, never sees another school's row; an ADMIN who picked no
     *       school reads across schools (D6), exactly as she does on `/admin/lessons/{id}`.</li>
     *   <li>A parent has no tenant scope at all — the Hibernate filter is off for her — so her side is checked
     *       explicitly: the lesson must be <em>published</em> and belong to the school of one of her children.</li>
     * </ul>
     */
    public void requirePage(String lessonId, Principals.Parent parent, Principals.User user) {
        var lesson = lessons.findOneById(lessonId).orElseThrow(MediaAccess::hidden);
        if (user != null) return;                                               // the filter above was the scope check
        if (parent == null) throw hidden();
        if (!"published".equals(lesson.getStatus())) throw hidden();
        if (!schoolsOf(parent).contains(lesson.getSchoolId())) throw hidden();
    }

    /**
     * `/media/child/{id}`: the child's own parent, or a dashboard user of the child's school (ADMIN any).
     *
     * <p>Every refusal {@link ChildService} makes is re-thrown as {@link #hidden()}: on its own it answers "child not
     * found" for a child that exists but is not the caller's, and the controller answers "media not found" for an id
     * that does not exist — two different bodies behind the same 404, which is the existence oracle this class is
     * here to close. Same status, same body, whatever the reason.
     */
    public void requireChild(String childId, Principals.Parent parent, Principals.User user) {
        if (user == null && parent == null) throw hidden();
        try {
            if (user != null) childService.scoped(childId); else childService.owned(childId, parent);
        } catch (ApiException e) { throw hidden(); }
    }

    /**
     * MH1: `/media/attachments/{id}`, by the broadcast the bytes are attached to.
     *
     * <ul>
     *   <li><strong>The uploader</strong> reads her own file whether or not it is attached to anything yet — she has
     *       to see it in the composer before she posts. Nobody else may read an unattached upload, which is why this
     *       is the only case answered without asking the broadcasts.</li>
     *   <li><strong>Everybody else</strong> reads it exactly when she may read a broadcast that carries it
     *       ({@link quest.server.broadcasts.BroadcastService#readsAttachment}) — the one predicate the feeds, the
     *       archives and the bell all use, so a weekly plan's image reaches that grade and nobody else.</li>
     * </ul>
     *
     * <p>The row was already fetched through the tenant-filtered {@code findOneById}, so a scoped dashboard caller
     * never gets this far with another school's id; a parent has no filter and her school is the row's own, checked
     * against the school of the child the broadcast reached. Anything else is 404, like everything else here.
     */
    public void requireAttachment(Entities.AttachmentEntity row, Principals.Parent parent, Principals.User user) {
        if (user != null && user.userId().equals(row.getUploadedBy())) return;
        if (user == null && parent == null) throw hidden();
        var kids = user != null ? List.<ChildEntity>of()
                : children.findByParentIdAndDeletedAtIsNullOrderByCreatedAt(parent.parentId());
        if (!broadcasts.readsAttachment(row.getId(), row.getSchoolId(), user, kids)) throw hidden();
    }

    /** The schools a parent reaches through her children; a parent with no child reaches none. */
    private Set<String> schoolsOf(Principals.Parent parent) {
        return children.findByParentIdAndDeletedAtIsNullOrderByCreatedAt(parent.parentId()).stream()
                .map(ChildEntity::getSchoolId).collect(Collectors.toSet());
    }

    /** The one refusal this class makes, whatever the reason. */
    private static ApiException hidden() { return ApiException.notFound("media"); }
}
