package quest.server.platform;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** §A: `PlatformSettings(name, shortName, logoUrl, supportEmail, defaultTheme)`, the Java mirror of the shared type. */
public final class PlatformDto {
    private PlatformDto() {}

    /**
     * `GET /platform-settings` is public and fills `name`, `shortName` and `logoUrl` only — enough for the browser
     * title, the sign-in heading and the footer. `GET`/`PUT /admin/platform-settings` fill every field.
     */
    public record PlatformSettings(String name, String shortName, String logoUrl, String supportEmail,
                                   ThemeDto.SchoolTheme defaultTheme) {}

    /** Only the fields that are present are written; `defaultTheme` is validated like any school theme. */
    public record UpdatePlatformSettingsRequest(@Size(max = 120) String name, @Size(max = 40) String shortName,
                                                @Size(max = 2000) String logoUrl, @Email String supportEmail,
                                                ThemeDto.SchoolTheme defaultTheme) {}
}
