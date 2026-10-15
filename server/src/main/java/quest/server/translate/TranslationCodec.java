package quest.server.translate;

import org.springframework.stereotype.Component;
import quest.api.dto.TranslateRequest;
import quest.api.dto.Translation;
import quest.server.config.ApiException;
import quest.server.config.Json;

/**
 * Feature 29's bodies through the shared kotlinx codec ({@code quest.api.dto.Translation.kt}), in
 * {@link quest.server.chat.ComplaintCodec}'s shape.
 *
 * <p>The decode is also the language allow-list: {@code lang} and {@code kind} are enums in the contract, so a
 * language outside the eight — or a kind that is not one of the four — never reaches
 * {@link TranslationService} at all and is the 400 that says what to send.
 */
@Component
public class TranslationCodec {
    private final Json json;
    public TranslationCodec(Json json) { this.json = json; }

    public TranslateRequest request(String body) {
        try { return json.decodeShared(body, TranslateRequest.Companion.serializer()); }
        catch (RuntimeException e) {
            throw ApiException.badRequest("Send {\"kind\": \"chat|broadcast|announcement|complaint\", \"id\": \"…\", \"lang\": \""
                    + String.join("|", ReadingLanguages.ALL) + "\"}.");
        }
    }

    public String translation(Translation t) { return json.encodeShared(t, Translation.Companion.serializer()); }
}
