package quest.feature.staff.data

import quest.api.dto.ChatThread
import quest.feature.chat.data.ChatSocketClient
import quest.feature.staff.domain.StaffGateway
import quest.feature.staff.domain.StaffThreads

class StaffThreadsImpl(
    private val api: StaffGateway,
    private val session: StaffSession,
    private val socket: ChatSocketClient,
) : StaffThreads {
    override val frames = socket.incomingFrames

    override suspend fun list(): List<ChatThread> = api.staffThreads().filter { it.id != null }

    override fun hasChat(): Boolean = session.profile.value?.hasChat ?: true

    override fun connect() = socket.connect()
}
