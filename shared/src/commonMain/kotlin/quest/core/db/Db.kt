package quest.core.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import quest.core.json.AppJson
import quest.core.platform.DriverFactory

/**
 * JSON codec for the cache. It is [AppJson], so a row written by a newer build — or a body cached before a field was
 * removed — still reads back instead of throwing on the unknown key (D16).
 */
val QuestJson: Json = AppJson

class Db(driverFactory: DriverFactory) {
    val database: QuestDatabase = QuestDatabase(driverFactory.create())
    val queries: QuestQueries get() = database.questQueries

    /** Runs a blocking DB block off the main thread. */
    suspend fun <T> read(block: QuestQueries.() -> T): T = withContext(Dispatchers.Default) { queries.block() }
    suspend fun write(block: QuestQueries.() -> Unit) = withContext(Dispatchers.Default) { queries.transaction { queries.block() } }
}
