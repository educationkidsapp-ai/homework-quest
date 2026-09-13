package quest.api

/**
 * The fixed illustration set. The model chooses from these keys and never invents an image.
 * Adding a key here also adds it to the JSON schema enum (see `SchemaSyncTest`) and needs a glyph in
 * `quest.core.design.Illustration` in the app.
 */
object Illustrations {
    val keys: List<String> = listOf(
        // sh
        "ship", "sheep", "shop", "shell", "shoe", "fish",
        // ch
        "chair", "cheese", "chick", "chips",
        // th
        "thumb", "three", "bath", "moth",
        // common nouns / CVC / sight words
        "sun", "sock", "cat", "dog", "hat", "bed", "cup", "pen", "pig", "bus", "fox",
        "apple", "ball", "tree", "bee", "moon", "star", "car",
    )

    fun isKnown(key: String) = key in keys
}
