package quest.feature.parent.domain

import quest.api.validation.Sha256

/** Salted, iterated SHA-256 for the 4-digit parent PIN. Stored format: `v1$<salt>$<hex>`. */
object PinHasher {
    private const val ITERATIONS = 5_000

    fun hash(pin: String, salt: String = randomSalt()): String {
        var digest = Sha256.digest((salt + pin).encodeToByteArray())
        repeat(ITERATIONS - 1) { digest = Sha256.digest(digest + salt.encodeToByteArray()) }
        return "v1$$salt$" + digest.joinToString("") { b -> ((b.toInt() and 0xff) or 0x100).toString(16).substring(1) }
    }

    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 3 || parts[0] != "v1") return false
        return hash(pin, parts[1]) == stored
    }

    private fun randomSalt(): String = (1..16).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
}
