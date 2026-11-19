# CAMBIOS — Jornadas del Sur · Guardia de mantenimiento

## Resumen

Se completaron los cuatro pedidos de la parte A (41, 42, 43, 44) sin cambiar de tecnología (Compose + Room + Retrofit) ni reemplazar el proyecto. No se hizo parte B.

- **Correr:** JDK 17, `./gradlew assembleDebug`, emulador API 26+. La Mesa (pestaña ⚙) controla el servicio local: versión, disponibilidad y demora.
- **Actualización desde la versión instalada:** el esquema Room pasa de v1 a v2 con `MIGRACION_1_2`, que conserva las charlas y las marcas.

## Cambios

### FIX · OkHttp explícito (`app/build.gradle.kts`)
- **Problema:** Retrofit 2.11 trae por transitiva OkHttp 3.14.9, y `lab/ServicioLocal.kt` usa la API de OkHttp 4 (`toMediaType`, `toResponseBody`).
- **Riesgo:** el proyecto no compila.
- **Cambio:** `implementation("com.squareup.okhttp3:okhttp:4.12.0")`.
- **Verificación:** `assembleDebug` OK y la app corre.

### A2 · 42 Agenda personal (`data/Programa.kt` → `CarteleraDao.aplicarEdicion`, `data/AgendaRepository.kt` → `sincronizar`)
- **Problema:** la sync armaba `Charla(favorita = false)` y hacía `@Insert(REPLACE)`; además nunca borraba las charlas retiradas.
- **Riesgo:** sync remoto → local que pisa datos del usuario. Cada descarga borraba todas las marcas, y una charla retirada (la 11 en v3) quedaba para siempre.
- **Cambio:** una sola `@Transaction` que hace, en orden:
  1. `DELETE … WHERE id NOT IN (:vigentes)`;
  2. `INSERT OR IGNORE` de las nuevas;
  3. `@Update(entity = Charla::class)` parcial con `DatosServidor`, sin la columna `favorita`.

  No lee una foto previa del estado local, así que una marca puesta o quitada durante la petición se respeta. Una charla retirada se borra con su marca y, si vuelve, entra sin marcar.
- **Verificación:** casos 42.x de la tabla.

### A1 · 41 Programa sin señal (`data/Programa.kt` → `Edicion` + `MIGRACION_1_2`, `Servicios.kt`, `ui/ProgramaModel.kt`, `ui/Catalogo.kt`)
- **Problema:**
  - cualquier error ocultaba la lista (`if (aviso) … else Grilla`);
  - no se podía distinguir "nunca descargué" de "edición vacía" (v4), porque las dos dan 0 filas.
- **Riesgo:** sin señal el usuario no ve los datos que sí tiene (offline-first); error ≠ vacío.
- **Cambio:**
  - **Tabla `edicion`** (fila única), escrita dentro de la misma `@Transaction` que aplica la edición.
  - **Base:** versión 2 con `MIGRACION_1_2`. El `CREATE TABLE` está copiado del `createAllTables` generado por Room. Si ya había charlas, la migración inserta la marca `(0, 0)`.
  - **ViewModel:** expone un único `uiState` con `sealed interface Contenido` (`Cargando` / `SinDescarga` / `SinCharlas` / `SinCoincidencias` / `Lista`), derivado en `derivarContenido(...)`.
  - **Pantalla:** el aviso «Sin conexión: se están usando los datos guardados.» + Reintentar va **encima** de la lista.
  - **Lista vacía:** un `[]` exitoso es una edición válida: vacía el catálogo, y con él las marcas, y deja la marca de edición.
- **Verificación:** se instaló encima de la versión con esquema 1 (sin desinstalar). No crashea; en `charlas` siguen las 12 filas y la marca; en `edicion` está la fila `(0, 0)`, puesta por la migración. Más los casos 41.x.

### A4 · 44 Control de descargas (`Servicios.kt`, `MainActivity.kt`, `ui/ProgramaModel.kt`, `ui/Catalogo.kt`)
- **Problema:** se recargaba en cada `onStart` (en cada giro o vuelta del fondo) y en el `LaunchedEffect(Unit)` del catálogo (al volver de una ficha o cambiar de pestaña). No había guardia contra el doble toque. `Servicios` (Room, Retrofit y contador) se creaba por ViewModel.
- **Riesgo:** ciclo de vida (lo que se dispara en `onStart` se repite), peticiones duplicadas o concurrentes, y varias instancias de la base.
- **Cambio:**
  - `Servicios.de(context)` es un singleton por proceso (`@Volatile` + `synchronized` + `applicationContext`) con la bandera `cargaAutomaticaHecha`.
  - `cargaAutomatica()` se llama desde `onCreate` **después** de aplicar el extra `servicio_disponible`, porque el interceptor captura la disponibilidad al comenzar la petición.
  - `recargar()` ignora los toques mientras el `Job` está activo.
  - Los filtros siguen siempre editables.
- **Verificación:** casos 44.x de la tabla.

### A3 · 43 Volver al punto de lectura (`ui/ProgramaModel.kt`)
- **Problema:** `tema` estaba en un `remember` del composable (se perdía al abrir una ficha y al girar) y `dia` vivía solo en el ViewModel (se perdía al matar el proceso).
- **Riesgo:** estado de pantalla guardado en un lugar que no sobrevive a la situación (recomposición, giro o muerte del proceso).
- **Cambio:** `dia` y `tema` en `SavedStateHandle` (`getStateFlow`), con acciones `elegirDia` y `elegirTema`. La UI ya no escribe estado. La ficha abierta ya estaba en un `rememberSaveable`. No se persisten en disco, así que una tarea nueva arranca sin filtros.
- **Verificación:** casos 43.x de la tabla.

## Evidencia de validación (pruebas manuales)

| Caso | Pasos | Esperado | Observado |
|---|---|---|---|
| 41.1 Migración con datos | Versión vieja (esquema 1) con v1 descargada y «Pruebas de interfaz» marcada → Mesa: disponible OFF → instalar encima | No crashea; 12 charlas + aviso de datos guardados; detalle y Mi agenda con la marca; `edicion` = (0, 0) | OK |
| 41.2 Falla con edición previa | Mesa: disponible OFF → Actualizar | Lista, detalle abierto y Mi agenda intactos + «Sin conexión: se están usando los datos guardados.» + Reintentar | OK |
| 41.3 v4 vacía | Mesa ON, v4 → Actualizar | «Sin charlas programadas» | OK |
| 41.4 v4 al reabrir sin señal | Después de 41.3: Mesa OFF → cerrar desde Recientes → reabrir | Sigue «Sin charlas programadas» + aviso; no aparece «Todavía no se descargó» | OK |
| 41.5 Arranque vacío sin señal | `adb shell pm clear ar.jornadas` y `adb shell am start -n ar.jornadas/.MainActivity --ez servicio_disponible false` | «Cargando programa…» → «Todavía no se descargó el programa» + Reintentar | OK |
| 41.6 Reintento | Después de 41.5: Mesa ON → Reintentar | Aparece el programa | OK |
| 42.1 v1 → v2 | v1, marcar la 11 y la 23 → v2 → Actualizar | La 11 sigue marcada, con «Compose en producción avanzada» y «Sala Puerto»; la 23 sigue marcada | OK |
| 42.2 v2 → v3 | v3 → Actualizar | La 11 desaparece del catálogo y de Mi agenda; entra la 90; la 23 sigue marcada | OK |
| 42.3 Regreso | v1 → Actualizar | La 11 vuelve sin marcar | OK |
| 42.4 Marca durante la petición | Demora larga → Actualizar → en los 8 s quitar una marca y agregar otra | Al llegar la respuesta se respetan los dos cambios | OK |
| 42.5 Persistencia | Cerrar desde Recientes y reabrir | Las marcas siguen | OK |
| 43.1 Giro | Día 2 + Datos → abrir «Cachés para viajeros» → Volver → rotar | Dos resultados de Datos | OK |
| 43.2 Muerte del proceso | Igual que 43.1, con la ficha abierta → Inicio → `adb shell am kill ar.jornadas` → volver desde Recientes → Volver | Se recupera la ficha y después los dos filtros | OK |
| 43.3 Tarea nueva | Cerrar desde Recientes → abrir desde el ícono | Ambos días / Todos | OK |
| 44.1 Una carga por proceso | Arranque en frío → rotar, entrar a una ficha, cambiar de pestaña | Contador de la Mesa = 1 | OK |
| 44.2 Doble toque | Demora larga → Actualizar dos veces rápido | El contador sube 1 | OK |
| 44.3 Volver a pedir | Después de un éxito o una falla → Actualizar | El contador sube 1 | OK |
| 44.4 Filtros durante la espera | Demora larga → Actualizar → cambiar Día y Tema | Responden enseguida | OK |

## Pendientes conocidos

| # | Hallazgo | Por qué no se hizo |
|---|---|---|
| 10 | `ProgramaApi.consultar()` devuelve `List` directo y el ViewModel solo atrapa `IOException`. Un `HttpException` o un JSON inválido (error de conversión) crashea. Corrección: que el Repository devuelva un resultado `sealed` (SinConexion / Servidor / Formato) | Tiempo. El servicio de prueba solo produce 200 o `IOException` |
| 11 | `FichaRemota` tiene campos no nulos y Gson puede meter `null`. Corrección: `String?` + `mapNotNull` | Tiempo |
| 12 | Si la ficha abierta se retira (v3 con la 11 abierta), el detalle queda en blanco. Corrección: mensaje «Esta charla ya no está en el programa» | Tiempo |
| 13 | `AgendaRepository` es una clase concreta sin interfaz y el VM toma `Servicios.de(app)`. No se puede inyectar un fake para tests | Tiempo. Requiere una factory explícita y una interfaz |
| 14 | Textos hardcodeados; no hay `res/strings.xml` | Es cosmético y no cuenta como mejora en la consigna |

**Alcance del singleton en 44, sin `Application`:**
- `Servicios.de()` guarda la instancia en un `companion object`, que vive lo que vive el proceso, igual que una clase `Application`.
- La bandera `cargaAutomaticaHecha` y el contador de la Mesa vuelven a cero solo cuando muere el proceso. Recrear la Activity (giro, o atrás y reabrir) no repite la carga.
- Guarda `applicationContext`, así que no retiene ninguna Activity (no hay fuga).
- Diferencia con una `Application`: la instancia se crea perezosamente en el primer `Servicios.de()` y no en el arranque del proceso. En esta app da lo mismo, porque se usa en el primer `onCreate`.
- La carga corre en `viewModelScope`: si el usuario sale con atrás durante la petición, se cancela. Al reabrir en el mismo proceso no hay otra carga automática; se pide con Actualizar.

## Registro de uso de IA

| Problema | Prompt | Respuesta útil | Decisión propia | Verificación |
|---|---|---|---|---|
| Reconocer el repo | Fase 0 solo lectura con una tabla de hallazgos | Mapa del proyecto, flujo del dato y los 14 hallazgos ligados a 41–44 | Orden 42 → 41 → 44 → 43; nada de B por tiempo | Revisión del código |
| Favoritas borradas en cada sync | Sync sin pisar `favorita` y sin foto previa | UPDATE parcial + INSERT IGNORE + DELETE de ausentes en una `@Transaction` | Mantener la columna `favorita` en `charlas` (sin tabla aparte) | SQL generado en `CarteleraDao_Impl` + casos 42.x |
| v4 vs. nunca descargado | Marca de edición + migración | Tabla `edicion` y `MIGRACION_1_2` que inserta la marca si había charlas | Room y no SharedPreferences; el CREATE sale del `createAllTables` generado | Instalación encima de v1 + Database Inspector |
| Recargas de más | Versión mínima de 44 | Singleton en `companion`, bandera por proceso, guardia por `Job` | Sin `Application` nueva, para ahorrar tiempo | Contador de la Mesa (44.x) |
| Filtros perdidos | 43 en un paso | `SavedStateHandle.getStateFlow` | No persistirlos en disco | `adb shell am kill` (43.2) |
