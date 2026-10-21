# Cambios

Formato por versión. El release de GitHub usa la sección de su versión como notas (tools/release.sh).

## 1.1.0 (2026-10-21)
- Actualizador seguro desde GitHub Releases. Sin servicios residentes: consulta `releases/latest` (ETag y User-Agent) solo al volver al home, como máximo una vez cada 24 h, nunca con reproducción activa ni en modo arte. También manual en Ajustes > Info > "Buscar actualizaciones".
- Aviso discreto "Actualización vX disponible" en la barra superior, con las mismas reglas antifatiga de Salud del deco: se oculta solo a las 12 h, "Más tarde" lo silencia 24 h y no vuelve antes de 24 h.
- Panel de actualización: descarga con progreso y cancelación (solo con espacio suficiente), verificación de SHA-256 contra `version.json`, verificación de que el certificado de firma del APK sea idéntico al de la app instalada (si no, se aborta y se borra el archivo) e instalación con PackageInstaller. Nunca es silenciosa: Android siempre pide confirmar. El APK descargado se borra al terminar o si falla.
- Ajustes > Info: "Actualizaciones automáticas" (Buscar y avisar, por defecto, o Desactivadas) y atribución de Open-Meteo (CC BY 4.0).
- Permiso nuevo: REQUEST_INSTALL_PACKAGES (la primera vez Android pide autorizar "instalar apps desconocidas" para Lienzo).
- Versionado: versionName 1.1.0, versionCode 3. `tools/release.sh` compila el release firmado, verifica la firma y publica APK, `version.json` y `SHA256SUMS`.
- Repositorio público: licencia MIT, NOTICE, README, CI de compilación y tests sin firma ni secretos.

## 1.0.0 (2026-10-20, última actualización)
- Salud del deco recalibrado: el criterio principal pasa a ser el tiempo encendido (5 días), porque la memoria de video libre fluctúa entre 50 y 196 MB en uso normal sin fallas y por sí sola no predice el problema. La CMA solo avisa si baja de 50 MB tras al menos 48 h encendido. Así el aviso deja de salir casi a diario.
- Salud del deco: umbral de memoria de video (CMA) en 130 MB (antes 150 MB), por encima de la falla real observada el 19-10 (Netflix, ~97 MB tras 43 h encendido; recién reiniciado hay ~347 MB). Aviso antifatiga: se evalúa solo al volver al home, como máximo cada 6 h, nunca con reproducción ni en modo arte; exige 2 evaluaciones bajas separadas por 30 min o más (cada una confirmada a los 20 s); el chip dura hasta que se abre o se descarta (se oculta solo a las 12 h) y no vuelve antes de 24 h; si la CMA supera el umbral + 30 MB o hay reinicio, desaparece y el contador parte de cero; por 7 días encendido, un aviso por semana. Ajustes > Info muestra "Último aviso".
- Medición en el deco (sin fallas de reproducción): en el home, CmaFree osciló entre 50 y 196 MB (muestra de 11 lecturas, 77 a 111 MB sin apps de video en caché), por lo que con 18 h encendido el aviso puede salir sin falla real.
- Rendimiento medido en el deco (antes a después): arranque en frío 659 a 587 ms (promedio de 3, con baseline profile compilado), PSS en reposo 33,2 a 31,4 MB, CPU 0% en ambos, dock con jank legacy 7,2% a 5,5% (promedio de 3 corridas).
- Clima: ícono y texto "Sin conexión" cuando no hay red. Chip "Obras n/60" durante la descarga de la colección, que desaparece al terminar.
- Alarmas y apagado nocturno: se reprograman al arrancar, al cambiar la hora o la zona horaria (TIME_SET, TIMEZONE_CHANGED) y tras actualizar Lienzo (MY_PACKAGE_REPLACED).
- Quitado el servicio de accesibilidad del botón Home (HomeKeyService), su opción en Ajustes y la mención en el asistente.
- Permisos: quitados KILL_BACKGROUND_PROCESSES y READ_MEDIA_IMAGES/READ_MEDIA_VIDEO. BootReceiver con `exported=false`.
- Baseline profile con reglas acotadas al arranque y al foco (`src/main/baseline-prof.txt`).
- Rendimiento: caché de imágenes por tamaño objetivo, hilos que expiran a los 10 s y conexiones cerradas al terminar.
- Empaquetado: se excluyen `kotlin/**` y `META-INF/*.version` (APK de 580 970 a 568 714 bytes en la versión final, con las funciones nuevas incluidas).

## 1.0.0 (2026-10-20, Mis fondos)
- Ajustes de Lienzo > Fondo > **Mis fondos**: importa imágenes y videos propios desde un pendrive USB o del almacenamiento del deco, sin ADB, sin servidores ni puertos abiertos.
  - **Importar desde USB**: pide el permiso estándar de medios. En Android 12 es READ_EXTERNAL_STORAGE. Lista las imágenes y videos de todos los volúmenes (MediaStore) en una grilla de miniaturas navegable con D-pad, marca varios con OK y muestra el tamaño total y el espacio libre antes de copiar. Progreso cancelable.
  - Imágenes: se ajustan a 1920x1080 en modo COVER con recorte centrado y se guardan como JPEG q85 en `Android/data/cl.deco.shelf/files/art_user` (con `index.json` propio, mismo formato que la colección). Las verticales y cuadradas llevan passe-partout y quedan fuera del fondo del home (home=false), solo salen en el protector.
  - Videos: solo H.264 o H.265, hasta 1920x1080 y 60 fps, sin HDR. Se rechazan los demás con el aviso "4K u otros formatos pueden afectar la reproducción de Netflix". Se copian sin recodificar a `files/video_user`.
  - Siempre quedan al menos 500 MB libres: si no alcanza, no se copia nada.
  - **Gestionar mis fondos**: lista y borra imágenes o videos propios (OK marca, OK de nuevo borra).
- **Fuente del fondo**: Colección curada (por defecto), Mis fondos o Ambas. Si Mis fondos está vacío usa la colección curada. Reemplaza la antigua opción "En línea", que no hacía nada.
- **Video del protector y del modo arte**: Monet (por defecto), un video propio o alternar todos.
- Permiso nuevo: READ_EXTERNAL_STORAGE (solo hasta Android 12, `maxSdkVersion` 32). Sin permisos de red nuevos.

## 1.0.0 (2026-10-20, Salud del deco v2)
- Panel "Salud del deco", abierto desde el aviso "Reiniciar recomendado" (o MENU sobre el aviso) y desde Ajustes > Info > "Salud del deco". Navegable con D-pad, con foco inicial en la acción recomendada.
  - Estado: memoria de video (CMA) libre y total, RAM disponible, tiempo encendido y recomendación.
  - "Cómo reiniciar": mantener presionado el botón de encendido del control y elegir Reiniciar (verificado en este firmware); Ajustes de Android > Preferencias del dispositivo > Reiniciar, con botón que abre Ajustes de Android; desenchufar 10 s.
  - "Recordarme más tarde": oculta el aviso 24 h.
  - "Liberar cachés de Lienzo": suelta solo las cachés de Lienzo y muestra RAM y CMA antes y después. No se ejecuta con reproducción activa.
  - Limitación verificada: Android 12 solo deja a una app cerrar procesos de su propio paquete. En este deco no cierra otras apps, y el panel lo indica. Si la CMA sigue baja, reiniciar.
- Aviso de salud con fondo oscuro y foco visible (antes el foco era un velo blanco casi invisible sobre obras claras).
- Ajustes: al volver de una subpágina con BACK, el foco regresa a la fila de origen (antes saltaba a la primera).

## 1.0.0 (2026-10-19, noche)
- Prioridad a la reproducción: Lienzo suelta su decodificador de video antes de abrir apps, en onPause y al terminar el protector.
- Salud del deco: aviso "Reiniciar recomendado" si la memoria de video (CMA) baja del umbral o el deco lleva más de 7 días encendido. CMA y tiempo encendido en Ajustes > Info. El criterio vigente está en la última actualización.
- instalar.sh ya no fuerza "frecuencia según contenido" (rompía Netflix y Movistar con DRM en este deco).
- Atenuación nocturna con amanecer y atardecer, y brillo por filtro de color.
- Modo seguro: watchdog de crashes en un proceso aparte; tras 3 fallos en 5 minutos arranca en modo mínimo.

## 1.0.0 (2026-10-19)
- Primera versión distribuible (versionCode 2).
- Home con apps, Continuar viendo, Top Shelf, reloj y alarma.
- Protector de pantalla con 60 obras y video de fondo.
- Exportar y restaurar configuración, modo seguro.
- Firma de release propia (antes: clave debug). Requiere desinstalar la versión debug la primera vez.
