package quest.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import quest.ui.design.MySchoolMark
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the vector mark to PNG. The 1024 px opaque render is the master the iOS `AppIcon` set is exported from
 * (`iosApp/iosApp/Assets.xcassets`), and the transparent one is the iOS launch image and the side-by-side comparison
 * against the source JPEG — so every raster of the logo in the repository comes out of the one vector drawing.
 */
@OptIn(ExperimentalComposeUiApi::class)
class BrandMarkRenderTest {
    private fun render(name: String, px: Int, markDp: Int, background: Color?): File {
        val scene = ImageComposeScene(px, px, Density(1f))
        try {
            scene.setContent {
                Box(Modifier.fillMaxSize().then(if (background != null) Modifier.background(background) else Modifier), contentAlignment = Alignment.Center) {
                    MySchoolMark(size = markDp.dp)
                }
            }
            val file = File(Screenshots.outDir, "$name.png")
            file.writeBytes(scene.render(0L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            return file
        } finally {
            scene.close()
        }
    }

    @Test fun appIconMaster() = assertTrue(render("brand-app-icon-1024", 1024, 660, Color.White).length() > 1000)

    @Test fun markOnTransparent() = assertTrue(render("brand-mark-512", 512, 512, null).length() > 1000)
}
