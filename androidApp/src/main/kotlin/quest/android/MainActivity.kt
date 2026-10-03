package quest.android

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.koin.android.ext.android.get
import quest.App
import quest.core.platform.AndroidTodaySnapshotStore
import quest.core.platform.BiometricHost
import quest.feature.today.domain.TodayLinks
import quest.core.platform.applyNightMode
import quest.feature.parent.domain.Appearance
import quest.feature.parent.domain.ParentRepository

/** A `FragmentActivity` because `BiometricPrompt` (M2) can only be shown from one. */
class MainActivity : FragmentActivity() {
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
        BiometricHost.attach(this)
        followWidgetTap(intent)
        setContent { App() }
    }

    /** A tap on the "Today" widget while the app is already open arrives here (`singleTop`). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        followWidgetTap(intent)
    }

    /** M3: hands the tap to the shared UI, which follows it underneath the biometric lock — never around it. */
    private fun followWidgetTap(intent: Intent?) = TodayLinks.open(intent?.getStringExtra(AndroidTodaySnapshotStore.EXTRA_LINK))

    override fun onDestroy() {
        BiometricHost.detach(this)
        super.onDestroy()
    }
}
