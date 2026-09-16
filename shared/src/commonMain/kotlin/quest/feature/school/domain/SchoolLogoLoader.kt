package quest.feature.school.domain

/**
 * Fetches a school's logo. `theme.logoUrl` is a public `https` URL (the server rejects anything else when Admin saves
 * a theme), so unlike the lesson pictures in `LessonImages` this carries no bearer token.
 *
 * Returns null for a URL that cannot be fetched or decoded; the caller draws the school's monogram instead, so a
 * broken logo is never an error the parent has to read.
 */
fun interface SchoolLogoLoader {
    suspend fun load(url: String): ByteArray?
}

/** Tests, screenshots and the admin preview: no network, always the monogram. */
object NoSchoolLogos : SchoolLogoLoader {
    override suspend fun load(url: String): ByteArray? = null
}
