package quest.core.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import quest.api.validation.SchemaValidator
import quest.core.platform.DriverFactory

/** JSON codec shared by the DB layer and the network layer (same settings as the validator). */
val QuestJson: Json = SchemaValidator.json

class Db(driverFactory: DriverFactory) {
    val database: QuestDatabase = QuestDatabase(driverFactory.create())
    val queries: QuestQueries get() = database.questQueries

    /** Runs a blocking DB block off the main thread. */
    suspend fun <T> read(block: QuestQueries.() -> T): T = withContext(Dispatchers.Default) { queries.block() }
    suspend fun write(block: QuestQueries.() -> Unit) = withContext(Dispatchers.Default) { queries.transaction { queries.block() } }
}
