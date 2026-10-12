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
import quest.server.push.Entities.StaffDeviceEntity;
import quest.server.tenancy.TenantContext;

/**
 * B4: the parent's phones (V32). Upsert by token — a token registered by a second parent moves to her, because the
 * phone is hers now — and at most {@link #MAX_DEVICES} per parent, the one seen longest ago dropped for a new one. The
 * parent is always the caller's own, from her Firebase token; nothing here reads a parent id from a request.
 *
 * <p>Feature 13: and the <strong>staff</strong> phones (V36, `staff_devices`), on the very same two routes. Which
 * table a registration lands in is the principal's, never the request's: a Firebase parent writes `parent_devices`
 * exactly as before, a dashboard or worker token writes `staff_devices`. A token <em>moves between</em> the two —
 * a teacher who hands her phone to her daughter, or signs in to the staff side herself — because the rule has always
 * been that one phone receives one person's news, and the two tables are two halves of one answer to "whose phone is
 * this". The per-person cap is the same ten.
 */
@Service
public class DeviceService {
    /** A family's phones and tablets, with room to spare; a reinstall leaves a dead token that FCM prunes later. */
    public static final int MAX_DEVICES = 10;
    /** An FCM token is ~160–200 characters; this is generous and still keeps a pasted essay out of the table. */
    static final int TOKEN_MAX = 4096, VERSION_MAX = 32;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_:\\-.]+");
    private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,3}");

    private final ParentDeviceRepository devices; private final StaffDeviceRepository staffDevices;
    private final TenantContext tenant; private final Clock clock;

    public DeviceService(ParentDeviceRepository devices, StaffDeviceRepository staffDevices, TenantContext tenant, Clock clock) {
        this.devices = devices; this.staffDevices = staffDevices; this.tenant = tenant; this.clock = clock;
    }

    @Transactional
    public void register(Principals.Parent parent, RegisterDeviceRequest request) {
        String parentId = require(parent);
        String token = token(request.getToken());
        String locale = locale(request.getLocale());
        String version = version(request.getAppVersion());
        var now = clock.instant();
        staffDevices.deleteByTokenValue(token);                                 // the phone is a parent's again
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

    /** Feature 13: the same thing for a member of staff, in `staff_devices` and in her own school. */
    @Transactional
    public void register(Principals.User caller, RegisterDeviceRequest request) {
        String userId = require(caller);
        String schoolId = tenant.writeSchoolId();
        String token = token(request.getToken());
        String locale = locale(request.getLocale());
        String version = version(request.getAppVersion());
        var now = clock.instant();
        devices.deleteByTokenValue(token);                                      // the phone is hers, not her child's
        var row = staffDevices.findByToken(token).orElseGet(() -> {
            var fresh = new StaffDeviceEntity();
            fresh.setId(UUID.randomUUID().toString()); fresh.setToken(token); fresh.setCreatedAt(now);
            return fresh;
        });
        row.setUserId(userId); row.setSchoolId(schoolId); row.setPlatform(request.getPlatform().name());
        row.setLocale(locale); row.setAppVersion(version); row.setLastSeenAt(now);
        try { staffDevices.saveAndFlush(row); }
        catch (org.springframework.dao.DataIntegrityViolationException raced) { throw ApiException.conflict("This phone is being registered already; try again."); }
        var mine = staffDevices.findByUserIdOrderByLastSeenAtDescIdAsc(userId);
        if (mine.size() > MAX_DEVICES) staffDevices.deleteAllInBatch(mine.subList(MAX_DEVICES, mine.size()));
    }

    /** Sign-out. A token she does not hold — someone else's, or one already pruned — changes nothing and is not an error. */
    @Transactional
    public void unregister(Principals.Parent parent, String token) {
        devices.deleteOwned(token(token), require(parent));
    }

    @Transactional
    public void unregister(Principals.User caller, String token) {
        staffDevices.deleteOwned(token(token), require(caller));
    }

    private static String require(Principals.Parent parent) {
        if (parent == null) throw ApiException.unauthorized("Sign in first.");
        return parent.parentId();
    }

    private static String require(Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        return caller.userId();
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
