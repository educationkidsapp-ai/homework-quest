package quest.feature.parent.data

import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.feature.parent.domain.ChildProfile
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.domain.ParentSettings
import quest.feature.parent.domain.PinHasher

class ParentRepositoryImpl(private val db: Db, private val settings: SettingsStore, private val childId: suspend () -> String) : ParentRepository {
    override val language get() = settings.language

    override suspend fun profile(): ChildProfile {
        val id = childId()
        val row = db.read { selectChild().executeAsOneOrNull() }
        return ChildProfile(
            id = id, name = row?.name ?: "", avatarColor = row?.avatarColor ?: "sun", grade = row?.grade?.toInt() ?: 1,
            curriculum = row?.curriculum ?: "international", languages = row?.languages?.split(",")?.filter { it.isNotBlank() } ?: listOf("en"),
            hasPin = row?.pinHash != null,
        )
    }

    override suspend fun saveProfile(name: String, grade: Int, curriculum: String, avatarColor: String) {
        val id = childId()
        val row = db.read { selectChild().executeAsOneOrNull() }
        db.write { upsertChild(id, name, avatarColor, grade.toLong(), curriculum, row?.languages ?: "en", row?.pinHash) }
    }

    override suspend fun setPin(pin: String) {
        val id = childId()
        val hash = PinHasher.hash(pin)
        db.write { updatePin(hash, id) }
    }

    override suspend fun verifyPin(pin: String): Boolean {
        val stored = db.read { selectChild().executeAsOneOrNull()?.pinHash } ?: return false
        return PinHasher.verify(pin, stored)
    }

    override suspend fun settings() = ParentSettings(settings.practiceLength(), settings.language())
    override suspend fun setPracticeLength(length: Int) { require(length in listOf(5, 7, 10)); settings.setPracticeLength(length) }
    override suspend fun setLanguage(code: String) = settings.setLanguage(code)
}
