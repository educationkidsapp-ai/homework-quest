package quest.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The MySchool mark, redrawn as a vector from `docs/brand/myschool-logo-source.jpg`: an open book seen from the front
 * — two magenta arcs meeting in a V over two orange ones — under a graduation cap, the blue diamond ring.
 *
 * The geometry is the source image's own, in its 1265 × 1208 frame. The same five path strings are the Android
 * launcher's vector drawable (`ic_launcher_foreground.xml`) and the iOS icon is rendered from this composable, so there
 * is one drawing of the logo, not three.
 */
object MySchoolMarkPaths {
    const val WIDTH = 1265f
    const val HEIGHT = 1208f

    /** The diamond ring: the outer and the inner rhombus, filled even-odd. */
    const val DIAMOND = "M630,110 L1220,385 L630,640 L45,385 Z M630,212 L272,378 L630,545 L990,378 Z"
    const val MAGENTA_LEFT = "M10,535 A685,685 0 0 1 632.5,934.1 L632.5,1135 L580.7,1135 A577,577 0 0 0 10,643 Z"
    const val MAGENTA_RIGHT = "M1255,535 A685,685 0 0 0 632.5,934.1 L632.5,1135 L684.3,1135 A577,577 0 0 1 1255,643 Z"
    const val ORANGE_LEFT = "M10,755 A465,465 0 0 1 467.2,1135 L359.8,1135 A360,360 0 0 0 10,860 Z"
    const val ORANGE_RIGHT = "M1255,755 A465,465 0 0 0 797.8,1135 L905.2,1135 A360,360 0 0 1 1255,860 Z"

    /** Where the diamond's gradient starts and ends in the frame: its left tip to its right tip. */
    val gradientStart = Offset(45f, 300f)
    val gradientEnd = Offset(1220f, 470f)
}

/**
 * The product's mark. It is the **product** brand — sign-in, the lock screen, About — and never replaces a school's own
 * logo where the school's logo is shown. [monochrome] draws every part in one colour, for a tinted or single-ink use.
 */
@Composable
fun MySchoolMark(modifier: Modifier = Modifier, size: Dp = 72.dp, monochrome: Color? = null, contentDescription: String = "MySchool") {
    val parts = remember {
        fun path(d: String, evenOdd: Boolean = false) = PathParser().parsePathString(d).toPath(Path()).apply { if (evenOdd) fillType = PathFillType.EvenOdd }
        Triple(
            path(MySchoolMarkPaths.DIAMOND, evenOdd = true),
            listOf(path(MySchoolMarkPaths.MAGENTA_LEFT), path(MySchoolMarkPaths.MAGENTA_RIGHT)),
            listOf(path(MySchoolMarkPaths.ORANGE_LEFT), path(MySchoolMarkPaths.ORANGE_RIGHT)),
        )
    }
    Canvas(modifier.size(size).semantics { this.contentDescription = contentDescription }) {
        // The frame is a little wider than tall; it is fitted to the square and centred.
        val unit = this.size.minDimension / MySchoolMarkPaths.WIDTH
        translate(top = (this.size.height - MySchoolMarkPaths.HEIGHT * unit) / 2f) {
            scale(unit, pivot = Offset.Zero) {
                val blue = monochrome?.let(::SolidColor)
                    ?: Brush.linearGradient(listOf(BrandColors.blue400, BrandColors.blue700), MySchoolMarkPaths.gradientStart, MySchoolMarkPaths.gradientEnd)
                drawPath(parts.first, blue)
                parts.second.forEach { drawPath(it, monochrome ?: BrandColors.magenta600) }
                parts.third.forEach { drawPath(it, monochrome ?: BrandColors.orange400) }
            }
        }
    }
}
