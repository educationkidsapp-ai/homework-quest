package quest.core.platform

import kotlinx.serialization.encodeToString
import quest.feature.today.domain.TodayJson
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults
import quest.feature.today.domain.ExamSitting
import quest.feature.today.domain.ExamSittingPresenter
import quest.feature.today.domain.TodaySnapshot
import quest.feature.today.domain.TodaySnapshotStore

/**
 * The two things only Swift can do. WidgetKit and ActivityKit have no Objective-C surface, so Kotlin cannot call them;
 * `iOSApp.swift` installs these when the app starts, and until it has, both are simply no-ops.
 */
object IosBridges {
    /** `WidgetCenter.shared.reloadAllTimelines()`. */
    var widgets: WidgetReloader? = null
    /** The exam Live Activity (iOS 16.2+): start or update with these values, or end it. */
    var examActivity: ExamActivityBridge? = null
}

interface WidgetReloader { fun reload() }

interface ExamActivityBridge {
    /** [closesAtMillis] is 0 when the end of the sitting is not known. */
    fun show(title: String, childName: String, closesAtMillis: Long, answered: Int, total: Int)
    fun end()
}

/**
 * The snapshot as one JSON string in the App Group's shared defaults, which is the only storage the widget extension
 * can read. The group id is the `APP_GROUP` entry of Info.plist (`group.<bundle id>`); without the entitlement the
 * suite is nil and nothing is written — the app works, the widget stays on its placeholder.
 */
class IosTodaySnapshotStore : TodaySnapshotStore {
    private val group: String? = (NSBundle.mainBundle.objectForInfoDictionaryKey("APP_GROUP") as? String)?.takeIf { it.isNotBlank() }

    override suspend fun write(snapshot: TodaySnapshot?) {
        val defaults = group?.let { NSUserDefaults(suiteName = it) } ?: return
        if (snapshot == null) defaults.removeObjectForKey(KEY) else defaults.setObject(TodayJson.encodeToString(snapshot), forKey = KEY)
        IosBridges.widgets?.reload()
    }

    companion object { const val KEY = "today.snapshot" }
}

class IosExamSittingPresenter : ExamSittingPresenter {
    override fun show(sitting: ExamSitting) { IosBridges.examActivity?.show(sitting.title, sitting.childName, sitting.closesAt ?: 0L, sitting.answered, sitting.total) }
    override fun end() { IosBridges.examActivity?.end() }
}
