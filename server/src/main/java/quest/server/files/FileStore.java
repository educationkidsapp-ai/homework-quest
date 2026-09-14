package quest.server.files;

import java.util.Optional;

/** Where bytes live: uploaded slides, rendered page images, children's recordings and drawings. */
public interface FileStore {
    record Stored(String path, String mimeType, long size) {}
    record Blob(byte[] bytes, String mimeType) {}

    Stored put(String path, byte[] bytes, String mimeType);
    Optional<Blob> get(String path);
    void delete(String path);
}
