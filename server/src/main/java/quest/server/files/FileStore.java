package quest.server.files;

import java.io.IOException;

/** Temporary storage for uploaded slides. Files live only until generation succeeds (or 24 h, whichever first). */
public interface FileStore {
    String put(String key, byte[] bytes, String mimeType) throws IOException;
    byte[] get(String key) throws IOException;
    void delete(String key) throws IOException;
}
