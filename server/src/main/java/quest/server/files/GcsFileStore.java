package quest.server.files;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.util.Optional;

/** Google Cloud Storage bucket (`uploads/` prefix carries the 24-hour lifecycle rule; media and page images are kept). */
public class GcsFileStore implements FileStore {
    private final Storage storage; private final String bucket;
    public GcsFileStore(String bucket) { this(StorageOptions.getDefaultInstance().getService(), bucket); }
    public GcsFileStore(Storage storage, String bucket) { this.storage = storage; this.bucket = bucket; }

    @Override public Stored put(String path, byte[] bytes, String mimeType) {
        storage.create(BlobInfo.newBuilder(BlobId.of(bucket, path)).setContentType(mimeType).build(), bytes);
        return new Stored(path, mimeType, bytes.length);
    }

    @Override public Optional<Blob> get(String path) {
        var blob = storage.get(BlobId.of(bucket, path));
        if (blob == null) return Optional.empty();
        return Optional.of(new Blob(blob.getContent(), blob.getContentType() == null ? "application/octet-stream" : blob.getContentType()));
    }

    @Override public void delete(String path) { storage.delete(BlobId.of(bucket, path)); }
}
