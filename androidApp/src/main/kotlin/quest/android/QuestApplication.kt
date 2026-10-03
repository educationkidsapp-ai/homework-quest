package quest.android

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import quest.core.platform.initialiseFirebasePush
import quest.di.ApiConfig
import quest.di.appModules

class QuestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // M5: push for parents, where this flavor has a google-services.json (see androidApp/build.gradle.kts).
        initialiseFirebasePush(this, BuildConfig.FCM_APP_ID, BuildConfig.FCM_PROJECT_ID, BuildConfig.FCM_SENDER_ID, BuildConfig.FCM_API_KEY)
        val config = if (BuildConfig.USE_FAKE_API) ApiConfig.Fake else ApiConfig.Server(BuildConfig.API_BASE_URL, BuildConfig.FIREBASE_API_KEY)
        startKoin {
            androidLogger()
            androidContext(this@QuestApplication)
            modules(appModules(config))
        }
    }
}
