package quest.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/** Homework Quest API — feature packages: auth, children, content, analysis, admin. All AI calls live in `analysis`. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@org.springframework.scheduling.annotation.EnableScheduling
public class ServerApplication {
    public static void main(String[] args) { SpringApplication.run(ServerApplication.class, args); }
}
