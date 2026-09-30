package quest.ui.design

import androidx.compose.ui.graphics.Color
import quest.api.dto.Subject

/**
 * Single source of truth for per-subject display properties: emoji, labels (EN/AR), and subject / world-map colours.
 * Every screen that shows a subject icon, colour, or label reads from here instead of inlining a binary
 * `if (subject == MATH) … else …` check.
 */
object SubjectMeta {
    data class Info(
        val emoji: String,
        val labelEn: String,
        val labelAr: String,
        val color: Color,
        val deepColor: Color,
    ) {
        fun label(rtl: Boolean): String = if (rtl) labelAr else labelEn
    }

    private val registry: Map<Subject, Info> = mapOf(
        Subject.MATH     to Info("🔢", "Math",      "رياضيات",       Palette.sand,              Color(0xFFC4A54A)),
        Subject.ENGLISH  to Info("📖", "English",   "إنجليزي",       Palette.lavender,          Color(0xFF7E63D8)),
        Subject.FRENCH   to Info("🇫🇷", "French",    "فرنسي",         Color(0xFF7EC8FF),         Color(0xFF5AAEEB)),
        Subject.SCIENCE  to Info("🔬", "Science",   "علوم",          Color(0xFF81C784),         Color(0xFF4CAF50)),
        Subject.RELIGION to Info("🕌", "Religion",  "تربية إسلامية", Color(0xFFFFCC80),         Color(0xFFFF9800)),
        Subject.ARABIC   to Info("📝", "Arabic",    "عربي",          Color(0xFFEF9A9A),         Color(0xFFE57373)),
    )

    fun of(subject: Subject?): Info = registry[subject] ?: registry[Subject.ENGLISH]!!
}
