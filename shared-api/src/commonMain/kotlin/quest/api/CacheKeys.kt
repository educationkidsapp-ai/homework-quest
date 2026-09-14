package quest.api

import quest.api.dto.Curriculum
import quest.api.dto.Subject
import quest.api.validation.Sha256

/**
 * The §4 cache-key rule, shared with the server so the admin panel can show the same keys.
 * Bump a prompt version to invalidate (old entries are kept).
 */
object CacheKeys {
    const val PROMPT_A_VERSION = "a1"
    const val PROMPT_B_VERSION = "b2"
    const val PROMPT_C_VERSION = "c2"

    fun sourceHash(fileHashes: List<String>, curriculum: Curriculum, grade: Int, subject: Subject, notes: String?): String =
        Sha256.hex((fileHashes.sorted().joinToString(",") + "|" + curriculum.name.lowercase() + grade + subject.name.lowercase() + "|" + Sha256.hex((notes ?: "").trim())).encodeToByteArray())

    fun analysisKey(sourceHash: String) = "$sourceHash|A|$PROMPT_A_VERSION"
    fun playKey(sourceHash: String, level: Int, variant: Int, seed: Int) = "$sourceHash|B|$level|$variant|$seed|$PROMPT_B_VERSION"
    fun stopKey(sourceHash: String, level: Int, variant: Int, stopIndex: Int, seed: Int) = "$sourceHash|B|$level|$variant|s$stopIndex|$seed|$PROMPT_B_VERSION"
    fun panelKey(sourceHash: String) = "$sourceHash|C|$PROMPT_C_VERSION"
}
