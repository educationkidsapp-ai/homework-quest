package quest.server.files;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.io.IOException;

/** Cloud Storage bucket with a 24-hour lifecycle rule (deploy/gcloud.sh). Uses the Cloud Run service identity. */
public class GcsFileStore implements FileStore {
    private final Storage storage = StorageOptions.getDefaultInstance().getService();
    private final String bucket;

    public GcsFileStore(String bucket) {
        if (bucket == null || bucket.isBlank()) throw new IllegalStateException("GCS_BUCKET is required when STORAGE=gcs");
        this.bucket = bucket;
    }

    @Override public String put(String key, byte[] bytes, String mimeType) throws IOException {
        storage.create(BlobInfo.newBuilder(BlobId.of(bucket, key)).setContentType(mimeType).build(), bytes);
        return key;
    }

    @Override public byte[] get(String key) throws IOException {
        var blob = storage.get(BlobId.of(bucket, key));
        if (blob == null) throw new IOException("missing " + key);
        return blob.getContent();
    }

    @Override public void delete(String key) throws IOException { storage.delete(BlobId.of(bucket, key)); }
}
