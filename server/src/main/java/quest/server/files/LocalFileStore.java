package quest.server.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Plain directory on disk — local development and tests. */
public class LocalFileStore implements FileStore {
    private final Path root;
    public LocalFileStore(Path root) { this.root = root; try { Files.createDirectories(root); } catch (IOException e) { throw new IllegalStateException(e); } }

    private Path resolve(String path) {
        var p = root.resolve(path).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("path escapes store: " + path);
        return p;
    }

    @Override public Stored put(String path, byte[] bytes, String mimeType) {
        try {
            var p = resolve(path); Files.createDirectories(p.getParent()); Files.write(p, bytes);
            Files.writeString(p.resolveSibling(p.getFileName() + ".mime"), mimeType);
            return new Stored(path, mimeType, bytes.length);
        } catch (IOException e) { throw new IllegalStateException("write failed: " + path, e); }
    }

    @Override public Optional<Blob> get(String path) {
        try {
            var p = resolve(path);
            if (!Files.exists(p)) return Optional.empty();
            var mimeFile = p.resolveSibling(p.getFileName() + ".mime");
            var mime = Files.exists(mimeFile) ? Files.readString(mimeFile) : "application/octet-stream";
            return Optional.of(new Blob(Files.readAllBytes(p), mime));
        } catch (IOException e) { throw new IllegalStateException("read failed: " + path, e); }
    }

    @Override public void delete(String path) {
        try { var p = resolve(path); Files.deleteIfExists(p); Files.deleteIfExists(p.resolveSibling(p.getFileName() + ".mime")); } catch (IOException e) { throw new IllegalStateException(e); }
    }
}
