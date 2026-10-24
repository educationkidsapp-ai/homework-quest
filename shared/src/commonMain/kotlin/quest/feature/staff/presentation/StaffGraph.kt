package quest.feature.staff.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import org.koin.compose.koinInject
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatThread
import quest.core.navigation.Routes
import quest.feature.auth.domain.SessionRoles
import quest.feature.lock.domain.AppLock
import quest.feature.staff.domain.SessionSide

/**
 * Feature 13: the staff half of the navigation graph.
 *
 * **Nothing here is reachable without a staff session.** [StaffGuard] stands in front of every route and sends a
 * parent, a child or a signed-out app straight back out again — a deep link, a restored back stack and a tapped push
 * all arrive through here, so one gate in one place is what makes "parents and children cannot reach any staff
 * screen" true rather than merely intended.
 */
fun NavGraphBuilder.staffGraph(nav: NavHostController) {
    composable<Routes.StaffHome> {
        StaffGuard(nav) {
            val lock: AppLock = koinInject()
            // The biometric lock is offered on the staff home exactly as it is on the parent home (M2).
            LaunchedEffect(lock) { lock.parentHomeOpened() }
            StaffHomeRoute(
                onMessages = { nav.navigate(Routes.StaffMessages) },
                onMyRecord = { nav.navigate(Routes.StaffMyRecord) },
                onMyBalance = { nav.navigate(Routes.StaffMyBalance) },
                onMyRequests = { nav.navigate(Routes.StaffMyRequests) },
                onNotifications = { nav.navigate(Routes.StaffNotifications) },
                onAccount = { nav.navigate(Routes.StaffAccount) },
                onParentSide = { nav.toParentSide() },
                onVoiceAnnouncement = { nav.navigate(Routes.StaffVoiceAnnouncement) },
            )
        }
    }
    composable<Routes.StaffMessages> {
        StaffGuard(nav) {
            StaffMessagesRoute(
                onOpenThread = { nav.navigate(it.asStaffConversation()) },
                onHome = { nav.toStaffHome() },
                onNotifications = { nav.navigate(Routes.StaffNotifications) { popUpTo(Routes.StaffHome) { inclusive = false } } },
                onAccount = { nav.navigate(Routes.StaffAccount) { popUpTo(Routes.StaffHome) { inclusive = false } } },
            )
        }
    }
    composable<Routes.StaffConversation> { entry ->
        StaffGuard(nav) {
            StaffConversationRoute(entry.toRoute<Routes.StaffConversation>(), onBack = { nav.popBackStack() })
        }
    }
    composable<Routes.StaffNotifications> {
        StaffGuard(nav) {
            StaffNotificationsRoute(
                onOpenThread = { threadId -> nav.navigate(Routes.StaffConversation(threadId = threadId)) },
                onHome = { nav.toStaffHome() },
                onMessages = { nav.navigate(Routes.StaffMessages) { popUpTo(Routes.StaffHome) { inclusive = false } } },
                onAccount = { nav.navigate(Routes.StaffAccount) { popUpTo(Routes.StaffHome) { inclusive = false } } },
            )
        }
    }
    composable<Routes.StaffAccount> {
        StaffGuard(nav) {
            StaffAccountRoute(
                onHome = { nav.toStaffHome() },
                onMessages = { nav.navigate(Routes.StaffMessages) { popUpTo(Routes.StaffHome) { inclusive = false } } },
                onNotifications = { nav.navigate(Routes.StaffNotifications) { popUpTo(Routes.StaffHome) { inclusive = false } } },
                onParentSide = { nav.toParentSide() },
                onSignedOut = { nav.navigate(Routes.SignIn) { popUpTo(0) { inclusive = true } } },
            )
        }
    }
    composable<Routes.StaffMyRecord> {
        StaffGuard(nav) {
            StaffMyRecordRoute(onBack = { nav.popBackStack() })
        }
    }
    composable<Routes.StaffMyBalance> {
        StaffGuard(nav) {
            StaffMyBalanceRoute(onBack = { nav.popBackStack() })
        }
    }
    composable<Routes.StaffMyRequests> {
        StaffGuard(nav) {
            StaffMyRequestsRoute(onBack = { nav.popBackStack() })
        }
    }
    composable<Routes.StaffVoiceAnnouncement> {
        StaffGuard(nav) {
            VoiceAnnouncementRoute(onBack = { nav.popBackStack() })
        }
    }
    composable<Routes.StaffChangePassword> {
        StaffGuard(nav) {
            StaffChangePasswordRoute(onChanged = { nav.toStaffHome() })
        }
    }
}

/**
 * The one door into the staff side. A route reached without a staff session draws nothing and leaves: to the parent
 * flow when that side is signed in, and to the sign-in screen when nothing is.
 */
@Composable
fun StaffGuard(nav: NavHostController, content: @Composable () -> Unit) {
    val roles: SessionRoles = koinInject()
    val sides by roles.sides.collectAsStateWithLifecycle()
    if (SessionSide.STAFF in sides) {
        content()
        return
    }
    LaunchedEffect(sides) {
        if (SessionSide.PARENT in sides) nav.navigate(Routes.ChildPicker) { popUpTo(0) { inclusive = true } }
        else nav.navigate(Routes.SignIn) { popUpTo(0) { inclusive = true } }
    }
}

private fun NavHostController.toStaffHome() = navigate(Routes.StaffHome) { popUpTo(Routes.StaffHome) { inclusive = true } }

/** The switch at the top of the staff home: her parent side, from its own first screen. */
private fun NavHostController.toParentSide() = navigate(Routes.ChildPicker) { popUpTo(0) { inclusive = true } }

/** One of her rows, with everything the header needs so the conversation draws itself without a second request. */
internal fun ChatThread.asStaffConversation(): Routes.StaffConversation {
    val withParent = peerRole == ChatPeerRole.PARENT
    return Routes.StaffConversation(
        threadId = id.orEmpty(),
        // On a parent thread the row names *her* in `teacherName`, so the header takes the parent (or the child, for
        // a roster child nobody has claimed yet).
        peerName = if (withParent) parentName?.takeIf { it.isNotBlank() } ?: childName else teacherName,
        peerUserId = if (withParent) "" else teacherId,
        childId = childId.takeIf { it.isNotBlank() },
        childName = childName.takeIf { it.isNotBlank() },
        withParent = withParent,
        peerRole = peerRole?.name,
    )
}
