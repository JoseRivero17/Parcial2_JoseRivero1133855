package ar.jornadas.ui

import android.app.Application
import androidx.lifecycle.*
import ar.jornadas.Servicios
import ar.jornadas.data.Charla
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException

/** Lo que muestra el catálogo: variantes excluyentes, se dibujan con un `when` exhaustivo. */
sealed interface Contenido {
    data object Cargando : Contenido
    data object SinDescarga : Contenido          // nunca hubo una edición exitosa
    data object SinCharlas : Contenido           // edición válida vacía (v4)
    data object SinCoincidencias : Contenido     // hay charlas, pero no pasan los filtros
    data class Lista(val charlas: List<Charla>) : Contenido
}

data class ProgramaUiState(
    val dia: Int = AMBOS_DIAS,
    val tema: String = TODOS_LOS_TEMAS,
    val cargando: Boolean = false,
    val usandoGuardados: Boolean = false,        // falló la última carga, pero hay edición previa
    val contenido: Contenido = Contenido.Cargando,
)

const val AMBOS_DIAS = 0
const val TODOS_LOS_TEMAS = "Todos"

private data class Filtros(val dia: Int, val tema: String)
private data class Carga(val enCurso: Boolean, val fallo: Boolean)

/** Regla pura: un solo lugar decide qué se muestra. */
internal fun derivarContenido(charlas: List<Charla>, hayEdicion: Boolean, dia: Int, tema: String,
                              cargando: Boolean): Contenido {
    if (!hayEdicion) return if (cargando) Contenido.Cargando else Contenido.SinDescarga
    if (charlas.isEmpty()) return Contenido.SinCharlas
    val filtradas = charlas.filter {
        (dia == AMBOS_DIAS || it.dia == dia) && (tema == TODOS_LOS_TEMAS || it.tema == tema)
    }
    return if (filtradas.isEmpty()) Contenido.SinCoincidencias else Contenido.Lista(filtradas)
}

class ProgramaModel(app: Application) : AndroidViewModel(app) {
    private val servicios = Servicios(app)
    val mesa = servicios.mesa
    private val repo = servicios.agenda

    /** Catálogo completo (detalle y Mi agenda): siempre lo que hay en la base, haya red o no. */
    val charlas = repo.programa.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val filtros = MutableStateFlow(Filtros(AMBOS_DIAS, TODOS_LOS_TEMAS))
    private val carga = MutableStateFlow(Carga(enCurso = false, fallo = false))

    val uiState: StateFlow<ProgramaUiState> =
        combine(charlas, repo.hayEdicion, filtros, carga) { lista, hayEdicion, f, c ->
            ProgramaUiState(
                dia = f.dia,
                tema = f.tema,
                cargando = c.enCurso,
                usandoGuardados = c.fallo && hayEdicion,
                contenido = derivarContenido(lista, hayEdicion, f.dia, f.tema, c.enCurso),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgramaUiState())

    fun elegirDia(dia: Int) = filtros.update { it.copy(dia = dia) }
    fun elegirTema(tema: String) = filtros.update { it.copy(tema = tema) }

    fun recargar() {
        viewModelScope.launch {
            carga.value = Carga(enCurso = true, fallo = false)
            try {
                repo.sincronizar()
                carga.value = Carga(enCurso = false, fallo = false)
            } catch (e: IOException) {
                carga.value = Carga(enCurso = false, fallo = true)
            }
        }
    }

    fun marcar(id: String) { viewModelScope.launch { repo.cambiarFavorita(id) } }
}
