# Cambios

Formato por versión. El release de GitHub usa la sección de su versión como notas (tools/release.sh).

## 1.2.0 (2026-10-25)
- Entradas: desplegable bajo la píldora (estilo tvOS) con HDMI, AV y TV en vivo, ícono por tipo, nombre personalizado ("ATV · HDMI 3") y punto verde en las entradas con señal. El cambio de entrada va dirigido a la app de TV en vivo (antes un visor de archivos podía reclamar el intent) y llega a la app aunque ya esté abierta (antes la TV se quedaba en la entrada anterior). El botón Entradas del control abre el mismo desplegable.
- Control remoto con asignación dinámica para cualquier control: Ajustes > Control remoto > "Asignar un botón" aprende la tecla y permite elegir Entradas, Ajustes, TV en vivo, la función original o cualquier app. Valores iniciales para el control TCL BT_RC813_MA (Netflix, Prime Video, YouTube, TCL Channel, Guard, Web, TCL Home, Ajustes). Flechas, OK, Atrás, Inicio, volumen y encendido nunca se reasignan.
- TV en vivo configurable (por defecto Movistar TV si está instalada): la usan el botón TV del control y la opción TV en vivo de Entradas.
- Botón Ajustes del control: en el home abre Ajustes; dentro de otra app abre el panel rápido del televisor (si el fabricante lo tiene). Ajustes suma la sección Televisor (ajustes rápidos, imagen y sonido).
- "Desinstalar app" del menú funciona (faltaba REQUEST_DELETE_PACKAGES y Android descartaba el pedido en silencio).
- Top Shelf: se pinta al instante desde caché y se refresca en segundo plano solo si venció; las tarjetas son alcanzables con el D-pad (UP desde el dock); el dock ya no espera la lectura del TvProvider; las imágenes repetidas se descargan una vez y las de tarjetas que ya no se ven se omiten.
- Ajustes: sin emojis, títulos uniformes, ruta de navegación, ayuda de teclas, "Liberar memoria de Lienzo" y PSS contra la meta. Favoritas del dock de 4 a 7.
- Coherencia visual: píldoras de la barra superior con foco blanco; el resto de los focos sigue el acento de la obra (antes las píldoras quedaban con el color del arranque).
- Rendimiento: abrir y cerrar la grilla sin rebind ni re-medición cuando no cambió "Continuar viendo" (TCL: 18% a 0% de cuadros lentos, p90 9 ms). Corregida una fuga del panel de ajustes que retenía la Activity anterior tras recreate().
- Servicio de botones en TCL: el sistema bloquea su arranque sin el appop APP_AUTO_START (ver README); Ajustes > Control remoto muestra si está activo o detenido.
- Botón y píldora Ajustes: menú para elegir Ajustes de Lienzo, ajustes rápidos del televisor (si el fabricante los tiene) o ajustes del sistema. Botón Ajustes del control TCL (4077) asignado por defecto. Un botón asignado a una app no instalada, o TV en vivo sin app elegida, deja pasar la tecla a su función original.
- Coherencia visual: Clima, Calendario, Temporizador, Alarma, Ajustes, menú de app y Salud usan el estilo de Entradas (tarjeta de vidrio y foco blanco con texto oscuro); la barra superior se atenúa bajo los paneles.
- Video del modo arte y del protector: elección del video e "Importar video o imágenes" en Modo arte y protector; solo se ofrecen videos que existen en el equipo y se avisa si no hay ninguno. La importación lee USB y almacenamiento interno.
- Entradas se oculta en equipos sin entradas de video (decos).
- Entradas con estado real: "Conectado" solo en HDMI con cable puesto (verificado en la TCL desenchufando y enchufando una Nintendo Switch: el estado de TvInputManager cambia con el cable) o con un equipo HDMI-CEC, que además muestra su nombre ("Nintendo Switch · HDMI 2"). AV no tiene detección y no muestra estado; si un equipo reporta todos los HDMI conectados, no se muestra nada. Sin conexión se marca la "Última usada". La lista se arma fuera del hilo principal y se actualiza sola.
- Control TCL BT_RC813_MA mapeado completo y verificado en la TV: TV en vivo (4001) abre la app de TV en vivo; Menú ≡ (4514) conserva el panel de ajustes rápidos de TCL; 123 abre Todas las apps y Perfil maneja el Modo arte (pulsación corta: entra con una obra o, ya dentro, pasa a la siguiente; mantenido 0,6 s: video al instante o vuelta a la obra); Información y CH quedan con su función original. Todas las apps y Modo arte se suman como acciones asignables a cualquier botón.
- Fondo "Color dinámico" (Pantalla y fondos > Fuente del fondo): base oscura con un resplandor del color de la app enfocada, con transición suave y sin decodificar obras en el home. El protector sigue usando la colección curada. "Fuente del fondo" se movió de Mis fondos a Pantalla y fondos.
- Memoria: íconos del dock, tarjetas del Top Shelf y Continuar viendo y miniaturas de importación en RGB_565 (la mitad de memoria; las esquinas se redondean al dibujar con un shader, sin clipping), halo de foco en ALPHA_8 y descarga de pósters a archivo temporal en vez de memoria. En la TCL, recorrer el dock pasó de 108 a 78 MB de PSS y la grilla de 93 a 82 MB.
- Configuración sin PC (Ajustes > Mantenimiento e información): con la depuración por red activa, Lienzo concede sus permisos, activa el servicio de botones (incluido el arranque automático de TCL), se fija como inicio y protector o devuelve el inicio al launcher original, sin computador. Cliente ADB local portado de Free My TV vía OmarchyTV (MIT), solo con comandos fijos. Accesos también desde Control remoto y Sistema y energía cuando falta algo.
- TV en vivo sin Movistar: el botón TV usa la app de TV en vivo del fabricante (Sony, Sharp, Google TV, DTVKit, TCL, Hisense, Philips, Xiaomi).
- Android 9 (Xiaomi Mi Box S): `minSdk` 28. Miniaturas, volúmenes de Mis fondos, lectura de formato de video e historial de cierres con alternativas para Android 9 y 10. Configuración sin PC deshabilita el launcher de sistema cuando Android 9 lo prefiere igual, y lo rehabilita al volver. Verificado en una MDZ-22-AB: 34 a 39 MB de PSS.
- Continuar viendo con SmartTube: su canal "Historial" se toma como "Seguir viendo" (SmartTube no publica Watch Next). Solo para SmartTube; en otras apps un historial no cuenta.
- Entradas se oculta en boxes de streaming sin HDMI ni AV (antes Pluto TV o Play Películas hacían aparecer "TV en vivo").
- Actualizador: la confirmación explícita de Android 12 se pedía también en Android 11, donde el método no existe.
- Video del modo arte: si el video de Monet no está en el equipo se usa el primer video propio (antes no se reproducía nada).
- Aviso al enchufar un equipo en un HDMI, con el estilo de Entradas: tarjeta de vidrio arriba a la derecha "Dispositivo conectado · Se conectó un equipo en HDMI 3" (o el nombre CEC del equipo) con "Cambiar a HDMI 3" y "Ahora no"; se oculta sola a los 12 s. Funciona con cualquier app en pantalla gracias al servicio de botones (TvInputCallback, API pública sin permisos); sin el servicio, solo en el home.
- Colección de arte: 57 obras nuevas liberadas como CC0 por The Met y la National Gallery of Art (vía Wikimedia Commons): impresionistas, escuela del río Hudson, paisaje holandés, naturalezas muertas, Hokusai e Hiroshige. Total 117.
- Menos recargas visibles: al volver de una app se conserva la caché reciente de íconos y tarjetas, el fondo no hace fundido si es la misma obra y el Top Shelf cambia directo cuando ya hay datos.
- Ajustes sin repetidos: una sola sección Televisor (ajustes rápidos, Wi-Fi, Bluetooth, imagen, sonido, almacenamiento, sistema), video solo en Modo arte y protector, Mantenimiento sin duplicar el panel Salud.
- Avisos de la barra superior (descarga de obras, espacio, salud, actualización) a la izquierda de las píldoras, sin taparlas.
- Correcciones tras una revisión de código con auditoría: bucle exponencial de refresco en el asistente (ANR), OOM al importar fotos verticales, pantalla negra al volver al home y reescritura del último fondo en cada regreso, doble decodificación del fondo, foco atrapado con el dock vacío, use-after-free al regenerar el desenfoque, reproductor de la app de TV en vivo destruido al abrirla desde Entradas, videos duplicados al reimportar, restaurar respaldo pisaba el apagado nocturno en curso, índice de Mis fondos perdido tras un corte de luz, Top Shelf vaciado por un error transitorio del provider, sello de app equivocado en Continuar viendo, MENU en modo mover y package visibility de los accesos rápidos.

## 1.1.3 (2026-10-21)
- Validación del reabrir automático: primera actualización hecha desde una versión que ya incluye esa función.

## 1.1.2 (2026-10-21)
- Lienzo se reabre solo al terminar una actualización iniciada desde el actualizador integrado (antes el deco quedaba en el launcher anterior). No ocurre con instalaciones por ADB.

## 1.1.1 (2026-10-21)
- Primera actualización probada por el actualizador integrado.

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
