package quest.server.files;

import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import quest.server.config.QuestProperties;

@Configuration
public class FileStoreConfig {
    @Bean
    public FileStore fileStore(QuestProperties props) {
        var s = props.storage();
        if (s != null && "gcs".equalsIgnoreCase(s.kind())) return new GcsFileStore(s.gcsBucket());
        return new LocalFileStore(Path.of(s == null || s.localDir() == null ? "data/files" : s.localDir()));
    }
}
