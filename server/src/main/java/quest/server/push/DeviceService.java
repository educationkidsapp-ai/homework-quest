package quest.server.push;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.RegisterDeviceRequest;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.push.Entities.ParentDeviceEntity;

/**
 * B4: the parent's phones (V32). Upsert by token — a token registered by a second parent moves to her, because the
 * phone is hers now — and at most {@link #MAX_DEVICES} per parent, the one seen longest ago dropped for a new one. The
 * parent is always the caller's own, from her Firebase token; nothing here reads a parent id from a request.
 */
@Service
public class DeviceService {
    /** A family's phones and tablets, with room to spare; a reinstall leaves a dead token that FCM prunes later. */
    public static final int MAX_DEVICES = 10;
    /** An FCM token is ~160–200 characters; this is generous and still keeps a pasted essay out of the table. */
    static final int TOKEN_MAX = 4096, VERSION_MAX = 32;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_:\\-.]+");
    private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,3}");

    private final ParentDeviceRepository devices; private final Clock clock;
    public DeviceService(ParentDeviceRepository devices, Clock clock) { this.devices = devices; this.clock = clock; }

    @Transactional
    public void register(Principals.Parent parent, RegisterDeviceRequest request) {
        String parentId = require(parent);
        String token = token(request.getToken());
        String locale = locale(request.getLocale());
        String version = version(request.getAppVersion());
        var now = clock.instant();
        var row = devices.findByToken(token).orElseGet(() -> {
            var fresh = new ParentDeviceEntity();
            fresh.setId(UUID.randomUUID().toString()); fresh.setToken(token); fresh.setCreatedAt(now);
            return fresh;
        });
        row.setParentId(parentId); row.setPlatform(request.getPlatform().name()); row.setLocale(locale); row.setAppVersion(version); row.setLastSeenAt(now);
        // Two registrations of one new token at the same moment: the loser is a 409 to retry, and never the driver's
        // message — a unique-key violation names the key's value, which here is the token.
        try { devices.saveAndFlush(row); }
        catch (org.springframework.dao.DataIntegrityViolationException raced) { throw ApiException.conflict("This phone is being registered already; try again."); }
        var mine = devices.findByParentIdOrderByLastSeenAtDescIdAsc(parentId);
        if (mine.size() > MAX_DEVICES) devices.deleteAllInBatch(mine.subList(MAX_DEVICES, mine.size()));
    }

    /** Sign-out. A token she does not hold — someone else's, or one already pruned — changes nothing and is not an error. */
    @Transactional
    public void unregister(Principals.Parent parent, String token) {
        devices.deleteOwned(token(token), require(parent));
    }

    private static String require(Principals.Parent parent) {
        if (parent == null) throw ApiException.unauthorized("Sign in first.");
        return parent.parentId();
    }

    private static String token(String raw) {
        String t = raw == null ? "" : raw.strip();
        if (t.isEmpty() || t.length() > TOKEN_MAX || !TOKEN.matcher(t).matches())
            throw ApiException.badRequest("token must be the FCM registration token, as Firebase handed it to the app.");
        return t;
    }

    /** `ar_SA` and `ar-sa` read alike: a BCP 47 tag, `_` taken for `-`, the language lower-cased. Blank is none. */
    static String locale(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String tag = raw.strip().replace('_', '-');
        if (tag.length() > 35 || !LOCALE.matcher(tag).matches()) throw ApiException.badRequest("locale must be a language tag such as en or ar-SA.");
        return Locale.forLanguageTag(tag).toLanguageTag();
    }

    private static String version(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.strip();
        if (v.length() > VERSION_MAX || v.chars().anyMatch(Character::isISOControl)) throw ApiException.badRequest("appVersion must be at most " + VERSION_MAX + " characters.");
        return v;
    }
}
