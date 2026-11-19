package ar.jornadas.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import retrofit2.http.GET
import kotlinx.coroutines.flow.Flow

data class FichaRemota(val id: String, val title: String, val track: String, val day: Int,
    val time: String, val room: String, val speaker: String, val summary: String)

interface ProgramaApi {
    @GET("programa") suspend fun consultar(): List<FichaRemota>
}

@Entity(tableName = "charlas")
data class Charla(@PrimaryKey val id: String, val titulo: String, val tema: String, val dia: Int,
    val hora: String, val sala: String, val ponente: String, val resumen: String,
    val favorita: Boolean = false)

/** Columnas de [Charla] que publica el servidor. No incluye `favorita`, que es dato del usuario. */
data class DatosServidor(val id: String, val titulo: String, val tema: String, val dia: Int,
    val hora: String, val sala: String, val ponente: String, val resumen: String) {
    fun comoNueva() = Charla(id, titulo, tema, dia, hora, sala, ponente, resumen, favorita = false)
}

@Dao
interface CarteleraDao {
    @Query("SELECT * FROM charlas ORDER BY dia, hora, sala")
    fun observar(): Flow<List<Charla>>

    /** Solo agrega las que no existen: una charla vigente conserva su fila (y su marca). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertarNuevas(charlas: List<Charla>)

    /** Actualización parcial: pisa solo las columnas del servidor, nunca `favorita`. */
    @Update(entity = Charla::class)
    suspend fun actualizarServidor(datos: List<DatosServidor>)

    @Query("DELETE FROM charlas WHERE id NOT IN (:vigentes)")
    suspend fun borrarAusentes(vigentes: List<String>)

    /**
     * Aplica una instantánea completa del programa, todo o nada.
     * No lee el estado local antes de escribir: una marca puesta o quitada durante la petición
     * queda intacta porque ninguna sentencia toca `favorita`.
     * Una charla retirada se borra con su marca; si vuelve, entra como nueva (sin marcar).
     * La marca de edición válida se escribe en la misma transacción que los datos.
     */
    @Transaction
    suspend fun aplicarEdicion(datos: List<DatosServidor>, descargadaEn: Long) {
        borrarAusentes(datos.map { it.id })
        insertarNuevas(datos.map { it.comoNueva() })
        actualizarServidor(datos)
        marcarEdicion(Edicion(descargadaEn = descargadaEn))
    }

    @Query("UPDATE charlas SET favorita = NOT favorita WHERE id = :id")
    suspend fun alternar(id: String)

    /** REPLACE a propósito: tabla de una sola fila (id fijo 0), sin relaciones ni cascadas. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun marcarEdicion(edicion: Edicion)

    @Query("SELECT EXISTS(SELECT 1 FROM edicion)")
    fun hayEdicion(): Flow<Boolean>
}

/**
 * Marca de "hay una edición válida descargada" (fila única). Distingue una edición vacía (v4)
 * de una instalación que nunca descargó: en ambos casos `charlas` tiene 0 filas.
 */
@Entity(tableName = "edicion")
data class Edicion(@PrimaryKey val id: Int = 0, val descargadaEn: Long)

@Database(entities = [Charla::class, Edicion::class], version = 2, exportSchema = false)
abstract class Archivo : RoomDatabase() {
    abstract fun cartelera(): CarteleraDao
}

/**
 * v1 → v2: agrega `edicion` sin tocar `charlas` (conserva charlas y favoritas).
 * El CREATE es copia exacta del createAllTables generado por Room (si difiere, falla la validación del esquema).
 * Quien ya tenía charlas descargó una edición válida en v1: se le crea la marca (descargadaEn = 0, desconocida).
 */
val MIGRACION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `edicion` (`id` INTEGER NOT NULL, `descargadaEn` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("INSERT INTO `edicion` (`id`, `descargadaEn`) SELECT 0, 0 WHERE EXISTS (SELECT 1 FROM `charlas`)")
    }
}
