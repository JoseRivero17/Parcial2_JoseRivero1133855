package ar.jornadas.data

import androidx.room.*
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
     */
    @Transaction
    suspend fun aplicarEdicion(datos: List<DatosServidor>) {
        borrarAusentes(datos.map { it.id })
        insertarNuevas(datos.map { it.comoNueva() })
        actualizarServidor(datos)
    }

    @Query("UPDATE charlas SET favorita = NOT favorita WHERE id = :id")
    suspend fun alternar(id: String)
}

@Database(entities = [Charla::class], version = 1, exportSchema = false)
abstract class Archivo : RoomDatabase() {
    abstract fun cartelera(): CarteleraDao
}
