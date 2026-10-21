# Lienzo

**Lienzo** es un launcher liviano para Android TV: una obra de arte de dominio público como fondo, tus apps favoritas en un dock, "Continuar viendo", reloj con alarma y temporizador, clima y feriados. Paquete `cl.deco.shelf`, licencia MIT.

> **English summary.** Lienzo is a lightweight Android TV launcher (Android 12+, Kotlin, plain Views, no analytics, no Compose/Leanback). It shows a public-domain artwork as wallpaper with a dock of favourite apps, a "Continue watching" row (Watch Next), clock, alarms and timers, weather and public holidays, a screensaver with video, a crash watchdog (safe mode) and a device health notice. It talks only to Open-Meteo, Nager.Date, Wikimedia and GitHub. Updates come from GitHub Releases and are verified before install: SHA-256 plus an identical signing certificate, installed through `PackageInstaller`, always with the system confirmation. Build with `./gradlew :app:assembleDebug`; releases are signed locally with `tools/release.sh` (no keys in CI). UI language: Spanish (Chile) with partial English date formats. Tested on a single device (DIW585, Android 12).

## Capturas

| | |
|---|---|
| ![Home](docs/screenshots/01-home.jpg) Home con el dock | ![Top Shelf](docs/screenshots/02-topshelf.jpg) Top Shelf |
| ![Grilla](docs/screenshots/03-grilla.jpg) Continuar viendo y todas las apps | ![Modo arte](docs/screenshots/09-modo-arte.jpg) Modo arte |
| ![Reloj](docs/screenshots/04-reloj.jpg) Calendario y feriados | ![Clima](docs/screenshots/06-clima.jpg) Pronóstico de 7 días |
| ![Ajustes](docs/screenshots/07-ajustes.jpg) Ajustes | ![Info](docs/screenshots/10-info.jpg) Info y salud del equipo |
| ![Temporizador](docs/screenshots/05-temporizador.jpg) Temporizador | ![Alarma](docs/screenshots/05b-alarma.jpg) Alarma |
| ![Ubicación](docs/screenshots/08-ubicacion.jpg) Ubicación y región | ![Accesos](docs/screenshots/11-accesos.jpg) Menú de app y accesos rápidos |

Las capturas tienen desenfocados los íconos de apps y el historial de reproducción.

## Funciones

- Home con apps favoritas, íconos adaptados u originales, renombrar, ocultar y mover.
- Top Shelf y "Continuar viendo" (Watch Next y canales de vista previa, vía `READ_TV_LISTINGS`).
- Fondo de obras de dominio público (60 obras de Wikimedia Commons), rotación configurable, atenuación nocturna según amanecer y atardecer, colores dinámicos desde la obra.
- Mis fondos: importa tus imágenes y videos desde un pendrive USB (sin red, sin ADB).
- Modo arte y protector de pantalla con video.
- Reloj, calendario con feriados (por país y región), temporizador y alarma.
- Clima de 7 días con Open-Meteo.
- Modo seguro: un watchdog de crashes arranca en modo mínimo tras 3 fallos en 5 minutos.
- Salud del equipo: aviso "Reiniciar recomendado" con reglas antifatiga y panel con memoria de video (CMA), RAM y tiempo encendido.
- Exportar y restaurar la configuración.
- Prioridad a la reproducción: Lienzo suelta su decodificador de video antes de abrir otra app.
- Actualizador seguro desde GitHub Releases (ver más abajo).

## Requisitos

- Android TV 12 o superior (`minSdk` 31). Probado solo en un decodificador DIW585 con Android 12. Otros equipos no están verificados.
- Para el modo sin ADB, un explorador de archivos o una app de descarga en el equipo.

## Instalación

Descarga `Lienzo-X.Y.Z.apk` desde [Releases](https://github.com/Parzval-Net/Lienzo_Launcher/releases).

### Con ADB (recomendada)

Activa la depuración por red en las opciones de desarrollador y ejecuta:

```sh
adb connect <IP-del-equipo>:5555
adb install -r -g Lienzo-X.Y.Z.apk
adb shell pm grant cl.deco.shelf android.permission.READ_TV_LISTINGS
adb shell pm grant cl.deco.shelf android.permission.WRITE_SECURE_SETTINGS   # apagado nocturno de pantalla
adb shell appops set cl.deco.shelf SYSTEM_ALERT_WINDOW allow                # alarma sobre otras apps
```

Después abre Lienzo: el asistente de primer inicio guía la elección de Lienzo como pantalla de inicio. Al terminar, desactiva la depuración por red si no la usas.

### Sin ADB

1. Copia el APK a un pendrive y ábrelo con un explorador de archivos, o súbelo con una app como Send Files to TV, o descárgalo con Downloader desde el enlace del release. Android pide permitir "instalar apps desconocidas" para esa app.
2. Abre Lienzo y sigue el asistente (permiso de Top Shelf, obras, pantalla de inicio).
3. Si Android no ofrece Lienzo como inicio, pulsa Home y elige Lienzo con "Siempre".

Sin ADB todo funciona, salvo el apagado nocturno de pantalla y la alarma sobre otras apps, que necesitan los permisos concedidos por ADB (la alarma abre Lienzo a pantalla completa en su lugar). El video de fondo no viene en el APK: usa Mis fondos para tus propios videos.

### Volver atrás

Desinstala Lienzo desde Ajustes de Android > Aplicaciones, y al pulsar Home elige tu launcher anterior. La desinstalación borra también los fondos importados y los permisos concedidos por ADB.

## Actualizaciones

Lienzo se actualiza desde los releases de este repositorio. Sin servicios ni tareas en segundo plano:

- Consulta `releases/latest` con `HttpURLConnection`, ETag y User-Agent, solo al volver al home, como máximo una vez cada 24 h, nunca con reproducción activa ni en modo arte. También a pedido: Ajustes > Info > "Buscar actualizaciones".
- Si hay un `versionCode` mayor (y el `minSdk` lo permite), aparece el chip "Actualización vX disponible" en la barra superior. Se oculta solo a las 12 h, "Más tarde" lo silencia 24 h y no vuelve antes de 24 h.
- Ajustes > Info > "Actualizaciones automáticas": Buscar y avisar (por defecto) o Desactivadas.

Cómo se protege la instalación:

| Paso | Protección |
|---|---|
| Descarga | Solo https hacia GitHub y su CDN de releases; el APK debe venir de este repositorio. Con progreso y cancelación, solo con espacio suficiente, con tope de tamaño. |
| Integridad | SHA-256 del APK contra el `version.json` del release. Detecta archivos corruptos. |
| Autenticidad | El certificado de firma del APK descargado debe ser **idéntico** al de la app instalada (`getPackageArchiveInfo` con `GET_SIGNING_CERTIFICATES`), además de paquete, `versionCode` anunciado y versión mayor a la instalada. Si algo falla, se aborta y se borra el archivo. Esto es lo que impide instalar un APK alterado: el hash viaja en el mismo release y no basta por sí solo. |
| Instalación | `PackageInstaller` por sesión con confirmación obligatoria del sistema. Nunca hay instalación silenciosa. La primera vez Android pide autorizar "instalar apps desconocidas" para Lienzo y el panel te lleva a esa pantalla. |
| Limpieza | El APK descargado se borra al terminar, al cancelar o si falla. |

La versión 1.0.0 no trae el actualizador: se pasa a 1.1.0 instalando el APK a mano. Si compilas tu propia copia con otra clave, Android rechazará las actualizaciones de este repositorio (firma distinta) y debes cambiar `UpdateRules.REPO` por tu fork.

### Verificar la firma de un release

```sh
apksigner verify --print-certs Lienzo-X.Y.Z.apk
shasum -a 256 -c SHA256SUMS
```

Huella SHA-256 del certificado de release: `7B:EA:3F:BF:E0:CF:1A:F9:D1:E4:58:DE:57:F0:CA:6B:F9:D7:64:77:88:A8:BB:D7:C2:59:C7:60:E2:53:62:2A`

## Privacidad

- Sin analítica, sin cuentas, sin servidores ni puertos abiertos.
- La red se usa solo para: Open-Meteo (clima y búsqueda de ciudades), Nager.Date (feriados), Wikimedia (obras de arte) y GitHub (actualizaciones: `api.github.com`, `github.com` y su CDN de releases). Si desactivas "Actualizaciones automáticas", GitHub solo se consulta cuando lo pides.
- Configuración, favoritas y fondos importados quedan en el almacenamiento de la app.
- Permisos: `INTERNET`, `ACCESS_NETWORK_STATE`, `READ_TV_LISTINGS` y `READ_EPG_DATA` (Continuar viendo), alarmas y arranque, `READ_EXTERNAL_STORAGE` (solo Android 12, para Mis fondos), `REQUEST_INSTALL_PACKAGES` (actualizador) y los opcionales concedidos por ADB (`WRITE_SECURE_SETTINGS`, `SYSTEM_ALERT_WINDOW`).

## Compilar desde el código

Requisitos: JDK 21 y Android SDK con la plataforma 35 (`sdk.dir` en `local.properties` o `ANDROID_HOME`).

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug     # tests JVM y APK debug
./gradlew :app:assembleRelease                          # sin keystore.properties usa la firma debug
```

El APK sale en `app/build/outputs/apk/`. Solo Android Views y RecyclerView: sin Compose, Leanback, Glide, OkHttp ni coroutines (ver `CONTRACTS.md`).

### Firmar uno mismo

La clave de este proyecto no está en el repositorio ni en CI. Para firmar tus propias compilaciones:

```sh
keytool -genkeypair -v -keystore mi-clave.jks -alias lienzo -keyalg RSA -keysize 4096 -validity 10000
```

Crea `keystore.properties` en la raíz (está en `.gitignore`):

```properties
storeFile=/ruta/absoluta/mi-clave.jks
storePassword=...
keyAlias=lienzo
keyPassword=...
```

Con eso `assembleRelease` firma con tu clave. Toda actualización futura de tu copia debe firmarse con la misma clave. Guárdala fuera de la nube y de git.

### Publicar un release (mantenedor)

Con el árbol limpio, el commit en `origin/main` y `keystore.properties` local:

```sh
tools/release.sh --dry-run   # compila, firma, verifica y genera dist/vX.Y.Z sin publicar
tools/release.sh             # crea el tag vX.Y.Z y el release con APK, version.json y SHA256SUMS
```

Las notas salen de la sección de la versión en `CHANGELOG.md`. La versión sale de `app/build.gradle.kts` (`versionName` y `versionCode`).

## Limitaciones conocidas

- Probado en un solo equipo (DIW585, Android 12, 2 GB de RAM). Otros equipos y Android 13 o superior no están verificados; Mis fondos está soportado solo en Android 12.
- Idioma de la interfaz: español de Chile; el inglés alcanza solo a fechas y unidades.
- El apagado nocturno de pantalla y la alarma sobre otras apps requieren ADB.
- Android 12 no permite a una app cerrar otras: "Liberar cachés" solo vacía las cachés de Lienzo; si la memoria de video sigue baja, hay que reiniciar el equipo.
- El video de fondo no se distribuye; se usan tus propios videos (H.264 o H.265, hasta 1080p y 60 fps).
- Open-Meteo gratuito es para uso no comercial.
- El actualizador necesita que Android permita "instalar apps desconocidas" para Lienzo; en algunos firmwares de TV esa pantalla no existe y hay que habilitarla en Seguridad y restricciones.
- Uso personal: instálalo solo en equipos propios y revisa las condiciones de comodato de tu operador.

## Créditos y licencia

MIT, ver [LICENSE](LICENSE). Atribuciones en [NOTICE](NOTICE): Free My TV (MIT), obras de dominio público vía Wikimedia Commons, Open-Meteo (CC BY 4.0) y Nager.Date (MIT). Historial de versiones en [CHANGELOG.md](CHANGELOG.md).
