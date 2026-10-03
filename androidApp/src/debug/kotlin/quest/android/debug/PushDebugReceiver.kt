package quest.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import quest.core.platform.AndroidPushNotifier
import quest.feature.push.domain.PushRegistration

/**
 * M5, debug builds only: hands a fake FCM data map to the very code `QuestMessagingService.onMessageReceived` calls, so
 * the notification and its tap can be checked on an emulator without a server able to send through FCM:
 *
 *     adb shell am broadcast -n app.homeworkquest.qa/quest.android.debug.PushDebugReceiver \
 *       --es kind chat.message --es title "Message from Ms Maya" --es body "Hala did well today." \
 *       --es childId c1 --es link /children/c1/chat/t-maya --es collapseKey chat:th-1 --es notificationId nt-chat
 *
 * It logs the registration state (never the token) under `QuestPush`.
 */
class PushDebugReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        val data = extras.keySet().mapNotNull { key -> extras.getString(key)?.let { key to it } }.toMap()
        Log.i("QuestPush", "registration=${get<PushRegistration>().status.value} keys=${data.keys.sorted()}")
        if (data.isNotEmpty()) get<AndroidPushNotifier>().handle(data)
    }
}
