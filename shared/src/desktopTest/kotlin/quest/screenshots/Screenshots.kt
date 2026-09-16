package quest.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import quest.ui.design.LocalAnimationsEnabled
import java.io.File

/**
 * Renders a composable in the design's 412×915 dp Android frame (density 2.625 → 1081×2401 px) and
 * writes a PNG to build/screenshots. Used by the per-screen screenshot tests.
 */
@OptIn(ExperimentalComposeUiApi::class)
object Screenshots {
    const val WIDTH_DP = 412
    const val HEIGHT_DP = 915
    const val DENSITY = 2.625f

    val outDir: File = File(System.getProperty("quest.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    fun render(name: String, frames: Int = 6, content: @Composable () -> Unit): File {
        val scene = ImageComposeScene(
            width = (WIDTH_DP * DENSITY).toInt(),
            height = (HEIGHT_DP * DENSITY).toInt(),
            density = Density(DENSITY),
        )
        try {
            // Looping animations (Pip's bounce and blink, the today-island glow) read the host's animation clock,
            // which on desktop is wall time rather than the frame times below — so the same screen rendered twice
            // differed by whatever the clock happened to be. Frozen here, every frame is a function of `frames` alone
            // and two runs of the suite produce byte-identical PNGs.
            scene.setContent { CompositionLocalProvider(LocalAnimationsEnabled provides false) { content() } }
            var image = scene.render(0L)
            // Advance a few frames so LaunchedEffects, animations and sheets settle.
            for (i in 1..frames) image = scene.render(i * 400_000_000L)
            val bytes = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            val file = File(outDir, "$name.png")
            file.writeBytes(bytes)
            return file
        } finally {
            scene.close()
        }
    }
}
