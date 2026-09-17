package quest.server.config;

import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;

/**
 * The `ContentApi` / `AdminApi` handlers return a body the kotlinx codec ({@link Json}) has already encoded, so they
 * declare their shared-api type with `@ApiResponse(… @Schema(implementation = …))` and springdoc reflects over the
 * Kotlin DTO to build the schema. Reflection is wrong for `kotlinx.datetime.LocalDate`: its bean properties
 * (`year`, `monthNumber`, `dayOfWeek`, …) are not what the codec writes — an ISO `2026-05-04` string is.
 */
@Configuration
public class OpenApiConfig {
    public OpenApiConfig() {
        SpringDocUtils.getConfig().replaceWithSchema(kotlinx.datetime.LocalDate.class, new StringSchema().format("date").example("2026-05-04"));
    }
}
