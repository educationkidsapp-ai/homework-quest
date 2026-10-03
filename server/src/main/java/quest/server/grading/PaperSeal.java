package quest.server.grading;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * B3: the opaque ids of one parent's copy of one sealed exam paper.
 *
 * <p>Every option, tile, item, pair and hotspot id of a sealed paper is replaced by {@link #opaque} — an HMAC under a
 * server secret of (parent, exam, stop, id) — so an id says nothing about the answer (generated content names them
 * `a`, `l1`/`r1`, `o1…o3`), and the lists are put in the order of those ids, which says nothing either. The same
 * parent always gets the same ids for the same exam, so a resumed sitting, a re-download and the grading of what
 * she sent all agree; the server maps them back with {@link #original} and nothing is stored. The scope is the
 * parent rather than the child because `GET /lessons/{id}` names no child.
 */
public final class PaperSeal {
    private final SecretKeySpec key; private final String scope;

    public PaperSeal(byte[] key, String parentId, String lessonId) {
        this.key = new SecretKeySpec(key, "HmacSHA256"); this.scope = parentId + "|" + lessonId;
    }

    /** The id a sealed paper shows for `id` of `stopId`. */
    public String opaque(String stopId, String id) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] h = mac.doFinal((scope + "|" + stopId + "|" + id).getBytes(StandardCharsets.UTF_8));
            return "x" + HexFormat.of().formatHex(h, 0, 8);
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    /** `ids` in the order a sealed paper lists them: by their opaque ids. */
    public List<String> order(String stopId, List<String> ids) {
        return ids.stream().sorted(Comparator.comparing((String id) -> opaque(stopId, id))).toList();
    }

    /**
     * A match stop on a sealed paper: pair `p` (listed in {@link #order}) shows the right-hand tile of pair
     * `owners.get(p)` — a second keyed permutation, unrelated to the pairing.
     */
    public Map<String, String> rightOwners(String stopId, List<String> pairIds) {
        var listed = order(stopId, pairIds);
        var tiles = pairIds.stream().sorted(Comparator.comparing((String id) -> opaque(stopId, "right|" + id))).toList();
        var out = new HashMap<String, String>();
        for (int i = 0; i < listed.size(); i++) out.put(listed.get(i), tiles.get(i));
        return out;
    }

    /** The original id behind an opaque one, or null when `token` is not one of them (an unsealed id, say). */
    public String original(String stopId, Collection<String> ids, String token) {
        for (String id : ids) if (opaque(stopId, id).equals(token)) return id;
        return null;
    }

    /** {@link #original}, keeping a token that is not opaque as it is. */
    public String back(String stopId, Collection<String> ids, String token) {
        String id = original(stopId, ids, token);
        return id == null ? token : id;
    }

    public List<String> back(String stopId, Collection<String> ids, List<String> tokens) {
        var out = new ArrayList<String>(tokens.size());
        for (String t : tokens) out.add(back(stopId, ids, t));
        return out;
    }
}
