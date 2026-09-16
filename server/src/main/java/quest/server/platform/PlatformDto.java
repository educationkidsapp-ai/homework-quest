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

    /**
     * Only the fields that are present are written; `defaultTheme` is validated like any school theme, and the name,
     * short name and logo go through {@link SafeText} exactly as a theme's `appName` and `logoUrl` do — the caps here
     * describe the contract, that class enforces it.
     */
    public record UpdatePlatformSettingsRequest(@Size(max = SafeText.MAX_NAME) String name,
                                                @Size(max = SafeText.MAX_NAME) String shortName,
                                                @Size(max = SafeText.MAX_URL) String logoUrl,
                                                @Email @Size(max = SafeText.MAX_EMAIL) String supportEmail,
                                                ThemeDto.SchoolTheme defaultTheme) {}
}
