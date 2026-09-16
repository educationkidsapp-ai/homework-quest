package quest.server.platform;

import org.springframework.data.jpa.repository.JpaRepository;

/** One row (`default`), seeded by `V5__flags_themes.sql`. Not a tenant table. */
public interface PlatformSettingsRepository extends JpaRepository<Entities.PlatformSettingsEntity, String> {
}
