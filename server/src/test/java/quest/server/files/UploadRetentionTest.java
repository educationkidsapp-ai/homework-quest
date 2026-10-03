package quest.server.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import quest.server.content.Entities.SourceFileEntity;
import quest.server.content.SourceFileRepository;

/**
 * The 24-hour sweep, since CR4 wrote a second blob for every upload. A `.md` is as much a copy of a school's lesson
 * as the PDF it came from, so it goes at the same time and the row stops pointing at it.
 */
class UploadRetentionTest {

    @Test void the_sweep_takes_the_extracted_markdown_with_the_file_it_came_from() {
        var store = new Store();
        var old = file("old", Instant.now().minus(30, ChronoUnit.HOURS));
        old.setMarkdownPath("uploads/old/slides.pdf.md"); old.setMarkdownChars(42);
        old.setConvertStatus("ready"); old.setConvertMethod("anydoc");
        store.put(old.getStoragePath(), new byte[] {1}, "application/pdf");
        store.put(old.getMarkdownPath(), "# text".getBytes(), "text/markdown");
        var fresh = file("fresh", Instant.now());
        fresh.setMarkdownPath("uploads/fresh/slides.pdf.md"); fresh.setConvertStatus("ready");
        store.put(fresh.getStoragePath(), new byte[] {1}, "application/pdf");
        store.put(fresh.getMarkdownPath(), "# still wanted".getBytes(), "text/markdown");

        retention(store, List.of(old, fresh)).sweep();

        assertThat(store.blobs).doesNotContainKeys(old.getStoragePath(), old.getMarkdownPath());
        assertThat(old.getMarkdownPath()).isNull();
        assertThat(old.getMarkdownChars()).isNull();
        assertThat(old.getConvertMethod()).isNull();
        assertThat(old.getConvertStatus()).isEqualTo("pending");
        assertThat(old.getDeletedAt()).isNotNull();
        // a file inside its day keeps both halves
        assertThat(store.blobs).containsKeys(fresh.getStoragePath(), fresh.getMarkdownPath());
        assertThat(fresh.getMarkdownPath()).isNotNull();
        assertThat(fresh.getDeletedAt()).isNull();
    }

    /**
     * B5: a chat file is kept for as long as the message it was sent with exists; one never sent — or whose message went
     * with its thread — is an orphan after its day, exactly as an unattached broadcast image is.
     */
    @Test void a_sent_chat_file_stays_and_an_unsent_or_orphaned_one_goes_after_its_day() {
        var store = new Store();
        var old = Instant.now().minus(30, ChronoUnit.HOURS);
        Entities.AttachmentEntity sent = attachment("sent", "m-live", old), unsent = attachment("unsent", null, old),
                orphan = attachment("orphan", "m-gone", old), fresh = attachment("fresh", null, Instant.now());
        for (var a : List.of(sent, unsent, orphan, fresh)) store.put(a.getStoragePath(), new byte[] {1}, "image/png");
        var attachments = Mockito.mock(AttachmentRepository.class);
        Mockito.when(attachments.findAll()).thenReturn(List.of(sent, unsent, orphan, fresh));
        Mockito.when(attachments.sentWithLiveMessage()).thenReturn(java.util.Set.of("sent"));
        var sources = Mockito.mock(SourceFileRepository.class);
        Mockito.when(sources.findAll()).thenReturn(List.of());
        var broadcasts = Mockito.mock(quest.server.broadcasts.BroadcastRepository.class);
        Mockito.when(broadcasts.referencedAttachmentIds()).thenReturn(java.util.Set.of());

        new UploadRetention(sources, store, attachments, broadcasts).sweep();

        assertThat(store.blobs).containsOnlyKeys(sent.getStoragePath(), fresh.getStoragePath());
        Mockito.verify(attachments).delete(unsent);
        Mockito.verify(attachments).delete(orphan);
    }

    private static Entities.AttachmentEntity attachment(String id, String messageId, Instant createdAt) {
        var a = new Entities.AttachmentEntity();
        a.setId(id); a.setSchoolId("s"); a.setUploadedBy("u"); a.setName("p.png"); a.setMimeType("image/png"); a.setSizeBytes(1);
        a.setStoragePath("attachments/s/" + id); a.setCreatedAt(createdAt); a.setPurpose(Entities.CHAT); a.setMessageId(messageId);
        return a;
    }

    /** MH1 added the attachment half; this suite is the slides half, so both new collaborators answer "nothing". */
    private UploadRetention retention(FileStore store, List<SourceFileEntity> rows) {
        var repo = Mockito.mock(SourceFileRepository.class);
        Mockito.when(repo.findAll()).thenReturn(rows);
        Mockito.when(repo.save(Mockito.any())).thenAnswer(i -> i.getArgument(0));
        var attachments = Mockito.mock(AttachmentRepository.class);
        Mockito.when(attachments.findAll()).thenReturn(List.of());
        var broadcasts = Mockito.mock(quest.server.broadcasts.BroadcastRepository.class);
        Mockito.when(broadcasts.referencedAttachmentIds()).thenReturn(java.util.Set.of());
        return new UploadRetention(repo, store, attachments, broadcasts);
    }

    private static SourceFileEntity file(String id, Instant createdAt) {
        var e = new SourceFileEntity();
        e.setId(id); e.setLessonId("lesson"); e.setFileName("slides.pdf"); e.setKind("pdf"); e.setMimeType("application/pdf");
        e.setFileHash(id); e.setStoragePath("uploads/" + id + "/slides.pdf"); e.setCreatedAt(createdAt);
        return e;
    }

    private static final class Store implements FileStore {
        final Map<String, Blob> blobs = new HashMap<>();
        @Override public Stored put(String path, byte[] bytes, String mimeType) { blobs.put(path, new Blob(bytes, mimeType)); return new Stored(path, mimeType, bytes.length); }
        @Override public Optional<Blob> get(String path) { return Optional.ofNullable(blobs.get(path)); }
        @Override public void delete(String path) { blobs.remove(path); }
    }
}
