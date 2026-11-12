# Chincheta — guía de desarrollo

Cómo está hecha Chincheta por dentro, qué se cambió respecto a Pinnit `v1.6.0` y cómo compilar, probar y publicar.
Pensada para quien retome el proyecto dentro de meses (incluido un asistente de IA).

## Punto de partida

- Base: [Pinnit `v1.6.0`](https://github.com/msasikanth/pinnit) (Apache 2.0, archivada en 2024). Kotlin, Room, Hilt,
  Mobius (Spotify) para la pantalla principal y el editor, Navigation, WorkManager para los recordatorios.
- En `v1.6.0` el `NotificationListenerService` estaba **vacío** (`// TODO: Complete this for v2`): la app solo creaba
  notas fijadas a mano. Todo el historial es nuevo.
- El paquete Kotlin sigue siendo `dev.sasikanth.pinnit` para que el diff con el original se lea fácil
  (`git diff v1.6.0`). El identificador de la app instalada es `io.github.stilton2.chincheta`.

## Mapa de lo añadido

Rutas relativas a `app/src/main/java/dev/sasikanth/pinnit/`.

| Pieza | Archivo | Qué hace |
|---|---|---|
| Historial | `background/services/NotificationsListener.kt` | Recibe cada notificación de otras apps y la guarda como entrada **sin fijar**. Se salta: la propia app, apps ignoradas, `ongoing` (música, navegación, descargas), resúmenes de grupo, categorías progress/transport/service y el mismo texto de la misma app en 10 min. En chats (MessagingStyle) guarda el último mensaje y, en grupos, `Remitente: texto`. Purga cada hora lo viejo. |
| Datos | `data/PinnitNotification.kt` | Entidad Room. Columnas nuevas `sourcePackage`, `sourceAppName` (BD v3, `Migration_2_3`) y `retracted` (v4, `Migration_3_4`). Una entrada con `sourcePackage == null` es una nota propia. Borrar = `deletedAt` (papelera). |
| Repositorio | `notifications/NotificationRepository.kt` | Consultas nuevas: historial, papelera, apps con historial, mover a papelera y deshacer, marcar `retracted`, exportar todo. |
| Ajustes | `data/preferences/HistorySettings.kt` | `SharedPreferences` (no el DataStore proto del original, porque el listener los lee en cada notificación): apps ignoradas, días de retención, reglas de fijado automático y bloqueo. |
| Fijada «como la original» | `utils/notification/NotificationUtil.kt`, `SourceAppIcons.kt` | La fijada usa el icono pequeño original de la app (se guarda por app en `filesDir/source_small_icons/<paquete>.png` cuando llega una notificación suya), el icono de la app como icono grande y su nombre como `subText`; al tocarla abre la app. **Sin botones**: desfijar y borrar solo desde dentro. |
| Fijadas que vuelven | `background/receivers/RepostNotificationReceiver.kt` | Desde Android 14 el usuario puede deslizar las `ongoing`. Cada fijada lleva un `deleteIntent`: si se descarta y sigue fijada, se vuelve a publicar a los 0,5 s. |
| Lista | `notifications/NotificationsScreen.kt` | Búsqueda (sin tildes, `utils/TextMatching.kt`), filtro por app, menú ⋮, pulsación larga (copiar, compartir, ver solo, ignorar), diálogos de borrar historial, apps ignoradas, retención, reglas, copia y bloqueo. El filtrado se hace en el fragment, después de que Mobius entregue la lista, para no tocar la lógica original. |
| Separadores | `notifications/adapter/DayHeaderDecoration.kt` | «Fijadas / Hoy / Ayer / fecha» dibujados con un `ItemDecoration`, así el adapter, el deslizar para borrar y la animación de fijar siguen trabajando con posiciones normales. |
| Filas | `notifications/adapter/NotificationsListAdapter.kt` | Icono y nombre de la app de origen; nota «🚫 Borrado por el remitente». Cada fila usa **su propia copia** del icono (`constantState.newDrawable().mutate()`): un `Drawable` compartido entre filas recibía el tinte de otra y salía como un círculo de color. |
| Papelera | `trash/TrashActivity.kt` | Actividad simple (sin Mobius): restaurar, borrar para siempre, vaciar. Se vacía sola a los 30 días. |
| Mensajes borrados | `utils/RetractedMessages.kt` | Detecta el texto que pone WhatsApp («Se eliminó este mensaje»…). No se guarda ese aviso: se marca `retracted` el mensaje original de ese chat. Probado con `RetractedMessagesTest`. |
| Copia de seguridad | `data/backup/HistoryBackup.kt` | JSON (`format: pinnit-backup`, `version: 1`) con todo y los ajustes. Importar mezcla por `uuid`, vuelve a mostrar fijadas y reprograma recordatorios. Usa `org.json` (sin dependencias). |
| Widget | `widget/PinnedWidgetProvider.kt`, `PinnedWidgetService.kt` | Lista de fijadas (`RemoteViewsService`). Se refresca desde `NotificationUtil.showNotification/dismissNotification`. |
| Bloqueo | `utils/AppLock.kt` | `androidx.biometric` (huella o PIN del móvil). Pide desbloqueo al abrir y al volver tras más de 1 min fuera; cancelar cierra la pantalla. Lo usan `MainActivity` y `TrashActivity`. |
| Permiso | `activity/MainActivity.kt` | Si falta el acceso a notificaciones, diálogo que abre los ajustes. |
| Marca | `res/drawable/chincheta_icon_*`, `res/mipmap-anydpi-v26/` | Icono propio (chincheta blanca sobre verde, glifo «push_pin» de Material, Apache 2.0). «Acerca de» acredita a Pinnit. |

Permisos añadidos: `QUERY_ALL_PACKAGES` (nombre e icono de las apps; impide publicar en Play tal cual) y los de
`androidx.biometric`. **No** hay permiso de Internet, y no debe haberlo: es la promesa de privacidad del README.

## Compilar

Requisitos: JDK 17 (Gradle 8.7 no arranca con Java 21+), Android SDK con `platforms;android-34` y `build-tools;34.0.0`,
y `local.properties` con `sdk.dir=...`.

```bash
JAVA_HOME=/ruta/jdk-17 ./gradlew assembleDebug testDebugUnitTest
JAVA_HOME=/ruta/jdk-17 ./gradlew assembleRelease -PVERSION_CODE=101 -PVERSION_NAME=1.0.1
```

- `versionCode`/`versionName` se pasan por `-P`; sin ellos el build sale como `1` / `1.0.0`.
- Firma: si existe `~/.android/pinnit-keystore.properties` (ver README) se firma con esa clave; si no, la release sale
  sin firmar. **Las actualizaciones tienen que firmarse con la misma clave** que la versión instalada.
- La variante `debug` lleva el sufijo `.debug` y LeakCanary (su icono abre LeakCanary; Chincheta se abre con
  `am start -n <paquete>.debug/dev.sasikanth.pinnit.SplashActivity`). En `debug` se puede leer la base de datos con
  `adb shell run-as <paquete>.debug cat databases/pinnit-db`.

## Probar

1. `./gradlew testDebugUnitTest` (tests del original + `RetractedMessagesTest`).
2. Emulador (AVD Pixel 6, Android 15 con Google Play). Arrancarlo **con ventana** y `-gpu auto`; sin ventana con
   `swiftshader_indirect` daba segfault. La imagen con Play pide aceptar «Allow USB debugging» a mano la primera vez.
3. Por `adb`:
   - Dar permisos: `cmd notification allow_listener <paquete>/dev.sasikanth.pinnit.background.services.NotificationsListener`
     y `pm grant <paquete> android.permission.POST_NOTIFICATIONS`.
   - Notificaciones de prueba: `adb shell "cmd notification post -S bigtext -t 'Título' etiqueta 'Texto'"` (todo dentro de
     una sola cadena; si no, adb parte las palabras).
   - Idioma solo para la app: `cmd locale set-app-locales <paquete> --locales es-ES`.
   - Barra de estado limpia para capturas: `settings put global sysui_demo_allowed 1` y
     `am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200`.
   - `uiautomator dump` puede fallar o devolver la pantalla vieja si la app no está quieta (los «hace X s» se
     refrescan cada segundo): borrar `/sdcard/ui.xml` antes y comprobar con una captura (`exec-out screencap -p`).
4. Lista de comprobaciones que se hizo antes de publicar: ver el apartado «Cómo se ha probado» del README.

Cosas del sistema que conviene saber al probar:

- Con PIN puesto, tras reiniciar no arranca nada de la app hasta desbloquear el móvil (Direct Boot). Es normal.
- Android 15+ entrega a apps como esta las notificaciones con códigos de un solo uso como «Contenido sensible oculto».
- Android 14+ deja deslizar las notificaciones `ongoing`; por eso existe `RepostNotificationReceiver`.
- Instalar por `adb` evita el aviso de «ajustes restringidos» al dar el acceso a notificaciones.

## Publicar una versión

1. Subir `VERSION_CODE` (va de 100 en adelante: 1.0.0 = 100, 1.0.1 = 101…) y `VERSION_NAME`.
2. Apuntar los cambios en [`CHANGELOG.md`](../CHANGELOG.md).
3. Compilar la release firmada y probarla (tests + emulador).
4. `git tag -a vX.Y.Z -m "Chincheta X.Y.Z"`, `git push <remoto> main vX.Y.Z`.
5. `gh release create vX.Y.Z chincheta-X.Y.Z.apk --title "Chincheta X.Y.Z" --notes-file notas.md`.
   El nombre del APK y la etiqueta `vX.Y.Z` es lo que miran Obtainium y los usuarios.

Los usuarios se actualizan con [Obtainium](https://github.com/ImranR98/Obtainium) apuntando a este repositorio.
La app no comprueba actualizaciones por sí misma porque no tiene Internet.

## Tiendas

- **Google Play**: no, por `QUERY_ALL_PACKAGES` y porque no es el objetivo.
- **IzzyOnDroid**: su política (consultada el 2026-11-12) rechaza apps con código generado por IA («Vibe-coded apps
  will be rejected»). Chincheta se hizo con Claude y se dice abiertamente, así que no se ha solicitado.
- **Verificación de desarrolladores de Google** (anunciada para 2026/2027): en móviles con Google las apps de fuera de
  Play podrían requerir un desarrollador registrado. Instalar por `adb` seguiría funcionando. Pendiente de ver cómo
  se aplica.
