package quest.android

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import quest.di.ApiConfig
import quest.di.appModules

class QuestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val config = if (BuildConfig.USE_FAKE_API) ApiConfig.Fake else ApiConfig.Server(BuildConfig.API_BASE_URL)
        startKoin {
            androidLogger()
            androidContext(this@QuestApplication)
            modules(appModules(config))
        }
    }
}
