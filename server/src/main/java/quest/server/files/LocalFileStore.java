package quest.server.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Disk-backed store for local development / docker-compose. */
public class LocalFileStore implements FileStore {
    private final Path root;

    public LocalFileStore(String dir) {
        this.root = Path.of(dir);
        try { Files.createDirectories(root); } catch (IOException e) { throw new IllegalStateException("cannot create upload dir " + dir, e); }
    }

    @Override public String put(String key, byte[] bytes, String mimeType) throws IOException {
        Path p = root.resolve(key);
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
        return key;
    }

    @Override public byte[] get(String key) throws IOException { return Files.readAllBytes(root.resolve(key)); }

    @Override public void delete(String key) throws IOException { Files.deleteIfExists(root.resolve(key)); }
}
