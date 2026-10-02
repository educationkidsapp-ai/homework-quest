package quest.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.koin.android.ext.android.get
import quest.App
import quest.core.platform.applyNightMode
import quest.feature.parent.domain.Appearance
import quest.feature.parent.domain.ParentRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The stored Light/Dark choice, before the window exists: a pinned choice picks the window theme itself (so
        // the ground behind the first frame is the right one on every API level) and is handed to the system, which
        // from API 31 draws the next cold start's splash screen in it. "System" leaves both to the device.
        val appearance = get<ParentRepository>().appearance.value
        when (appearance) {
            Appearance.LIGHT -> setTheme(R.style.Theme_Quest_Light)
            Appearance.DARK -> setTheme(R.style.Theme_Quest_Dark)
            Appearance.SYSTEM -> Unit
        }
        applyNightMode(this, dark = appearance == Appearance.DARK, followsSystem = appearance == Appearance.SYSTEM)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
