package quest.core.json

import kotlinx.serialization.json.Json
import quest.api.validation.SchemaValidator

/**
 * The one `Json` the **app** reads server bodies and its own cache with.
 *
 * It is `SchemaValidator.json`'s settings with `ignoreUnknownKeys = true`, and that single difference is the whole
 * point (D16, `docs/runbook.md` "The app and the contract"). `SchemaValidator.json` stays strict because the schema
 * tests and the generator want to know the moment model output grows a field nobody declared; an installed app wants
 * the opposite. Because the server encodes with `encodeDefaults = true`, a Kotlin default on a new field buys nothing
 * on the wire — the field is written into every response — so a strict app binary throws `SerializationException` on
 * the first body from a newer server rather than ignoring what it does not know.
 *
 * With this Json the app is forward-compatible instead: a server release that adds `ReleasedResult.weight` or
 * `PublishedLesson.somethingNew` is invisible to a binary that predates it, and app and server stop having to ship
 * together. Removing a field or changing its type is still breaking, and still belongs in the release notes.
 *
 * `classDiscriminator = "type"`, `encodeDefaults = true` and `explicitNulls = false` must match the server's encoder
 * exactly; only the unknown-key policy differs.
 */
val AppJson: Json = Json(SchemaValidator.json) { ignoreUnknownKeys = true }
