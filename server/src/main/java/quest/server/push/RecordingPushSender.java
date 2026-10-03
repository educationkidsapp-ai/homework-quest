package quest.server.push;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quest.api.dto.DevicePlatform;
import quest.api.dto.PushMessage;

/**
 * `quest.push.enabled=false` — H2, tests, a laptop: nothing leaves the process. What would have been sent is kept (the
 * last {@link #KEEP}) so a test can assert on it, and a test may script what "FCM" answers a token next.
 */
public class RecordingPushSender implements PushSender {
    private static final Logger log = LoggerFactory.getLogger(RecordingPushSender.class);
    static final int KEEP = 500;

    public record Sent(String token, DevicePlatform platform, PushMessage message) {}

    private final Deque<Sent> sent = new ArrayDeque<>();
    private final Map<String, Deque<Outcome>> scripted = new ConcurrentHashMap<>();

    @Override public Outcome send(String token, DevicePlatform platform, PushMessage message) {
        var next = scripted.get(token);
        Outcome outcome = next == null ? null : next.pollFirst();
        if (outcome == null) outcome = Outcome.SENT;
        if (outcome == Outcome.SENT) synchronized (sent) {
            sent.addLast(new Sent(token, platform, message));
            if (sent.size() > KEEP) sent.removeFirst();
        }
        log.debug("push (recorded): {} → {}", PushMessage.Companion.kindName(message.getKind()), outcome);
        return outcome;
    }

    /** Everything sent so far, oldest first. */
    public List<Sent> sent() { synchronized (sent) { return List.copyOf(sent); } }

    /** The next answers for this token, in order; after them every send succeeds again. */
    public void script(String token, Outcome... outcomes) {
        scripted.computeIfAbsent(token, t -> new java.util.concurrent.ConcurrentLinkedDeque<>()).addAll(List.of(outcomes));
    }
}
