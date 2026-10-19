# Shelf: contratos y reglas para agentes

Launcher Android TV liviano estilo tvOS para el deco Entel DIW585.
Hardware: 4x Cortex-A53 1.5 GHz, armeabi-v7a, Mali-450 (GLES 2.0), 2 GB RAM low_ram, Android 12, 1080p density 320.
Spec de diseño: ../launcher-spec.md (tiles 5:3, no 16:10). Referencia visual: ../re/at4k-screens/home2.png (solo inspiración, no copiar).

## Reglas
- Solo Android Views + RecyclerView. Sin Compose, Leanback, Glide, OkHttp, coroutines ni nuevas dependencias sin justificar.
- Red: HttpURLConnection + org.json. Todo bloqueante en Executors propios, nunca en main thread.
- Sin blur en tiempo real (RenderEffect). Blur precalculado una vez por imagen con util/Blur.
- Presupuesto: PSS < 40 MB en reposo, CPU ~0% idle, frame < 16 ms navegando.
- Cada agente toca SOLO sus carpetas. El manifest lo administra el orquestador (ya declara INTERNET, READ_TV_LISTINGS, READ_EPG_DATA y dream.ArtDreamService).
- Compilar con `./gradlew :app:assembleRelease` (si hay lock de Gradle, esperar). Estilo: Kotlin idiomático, comentarios breves en español, como el código existente.

## Propiedad de carpetas (cl.deco.shelf)
| Agente | Carpetas |
|---|---|
| UI | ui/, apps/, res/ (excepto res/xml/dream.xml), App.kt (Graph) |
| Datos | data/**, util/Blur.kt |
| Shelf+Dream | shelf/**, dream/** |

## Interfaces (no cambiar firmas sin avisar al orquestador)
- data.weather.WeatherSource.get(forceRefresh): Weather?   impl: data.weather.OpenMeteoWeather(context)
- data.holidays.HolidaySource.forYear(year): List<Holiday> impl: data.holidays.ChileHolidays(context)
- data.art.ArtSource.next()/prefetch(max)                  impl: data.art.MuseumArt(context)
- shelf.TopShelfProvider.itemsFor(pkg): List<TopShelfItem> impl: shelf.TvProviderShelf(context)
- util.Blur.blurred(src: Bitmap, radius: Int = 20, downscale: Int = 8): Bitmap
- Constructores con solo `Context` para que UI los instancie en App.kt.
