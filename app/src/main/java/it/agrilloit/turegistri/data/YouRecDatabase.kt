package it.agrilloit.turegistri.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Modello dati YouRec: un Project raggruppa i take registrati ("Continue recording")
 * e contiene l'edit (lista clip salvata come JSON in [ProjectEntity.editJson]).
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Percorso della miniatura (frame del primo take). */
    val thumbnailPath: String? = null,
    val exported: Boolean = false,
    val lastExportPath: String? = null,
    /** Stato dell'editor: lista clip JSON (vuoto = non ancora montato). */
    val editJson: String = "",
)

/** Un take = una sessione di registrazione (cartella con cam*.mp4, composito.mp4, wav, session.json). */
@Entity(tableName = "takes")
data class TakeEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val dirPath: String,
    val startedAt: Long,
    val durationMs: Long = 0,
    val compositePath: String? = null,
    /** Preferito per l'editor: composito (con audio) oppure cam0. */
    val hasAudio: Boolean = false,
    val markersJson: String = "",
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC LIMIT 1")
    suspend fun mostRecent(): ProjectEntity?

    @Insert
    suspend fun insert(project: ProjectEntity)

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TakeDao {
    @Query("SELECT * FROM takes WHERE projectId = :projectId ORDER BY startedAt ASC")
    fun observeForProject(projectId: String): Flow<List<TakeEntity>>

    @Query("SELECT * FROM takes WHERE projectId = :projectId ORDER BY startedAt ASC")
    suspend fun forProject(projectId: String): List<TakeEntity>

    @Query("SELECT * FROM takes WHERE id = :id")
    suspend fun get(id: String): TakeEntity?

    @Insert
    suspend fun insert(take: TakeEntity)

    @Update
    suspend fun update(take: TakeEntity)

    @Query("DELETE FROM takes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT projectId, COUNT(*) as cnt FROM takes GROUP BY projectId")
    suspend fun counts(): List<ProjectCount>
}

/** Conteggio take per progetto. */
data class ProjectCount(val projectId: String, val cnt: Int)

@Database(entities = [ProjectEntity::class, TakeEntity::class], version = 1, exportSchema = false)
abstract class YouRecDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun takeDao(): TakeDao

    companion object {
        @Volatile
        private var instance: YouRecDatabase? = null

        fun get(context: Context): YouRecDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    YouRecDatabase::class.java,
                    "yourec.db"
                ).build().also { instance = it }
            }
    }
}
