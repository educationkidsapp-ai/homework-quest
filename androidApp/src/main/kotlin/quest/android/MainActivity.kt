package quest.android

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.koin.android.ext.android.get
import quest.App
import quest.core.platform.AndroidTodaySnapshotStore
import quest.core.platform.AppVisibility
import quest.core.platform.BiometricHost
import quest.core.platform.PushIntents
import quest.feature.push.domain.isNewLaunch
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
        // M5: a tapped push that started the app — but not the same old intent again after process death or from Recents.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (isNewLaunch(restored = savedInstanceState != null, fromHistory = fromHistory)) PushIntents.follow(intent)
        setContent { App() }
    }

    // M5: while a screen of the app shows, a push refreshes the badges instead of posting a notification.
    override fun onStart() { super.onStart(); AppVisibility.visible = true }
    override fun onStop() { AppVisibility.visible = false; super.onStop() }

    /** A tap on the "Today" widget or on a push while the app is already running arrives here (`singleTop`). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        followWidgetTap(intent)
        PushIntents.follow(intent)                      // M5: a tapped push while the app was in the background or open
    }

    /** M3: hands the tap to the shared UI, which follows it underneath the biometric lock — never around it. */
    private fun followWidgetTap(intent: Intent?) = TodayLinks.open(intent?.getStringExtra(AndroidTodaySnapshotStore.EXTRA_LINK))

    override fun onDestroy() {
        BiometricHost.detach(this)
        super.onDestroy()
    }
}
