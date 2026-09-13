package quest.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/** Homework Quest API. All AI calls happen here; the mobile app never holds the Anthropic key. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class ServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
