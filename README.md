# Chincheta

**Historial de notificaciones y notificaciones fijadas para Android.**
Basada en [Pinnit](https://github.com/msasikanth/pinnit) de Sasikanth Miriyampalli.

> *English:* Chincheta is a fork of [Pinnit](https://github.com/msasikanth/pinnit) (Apache 2.0, archived in 2024).
> It adds a notification history, pinned notifications that look like the original ones and can't be swiped away,
> search, per-app filters, trash, backup, auto-pin rules, a home screen widget and an optional fingerprint lock.
> Everything stays on the phone: the app has no Internet permission. It was built and tested with Claude Code
> (see [How it was made](#cómo-se-ha-hecho)).

---

## Créditos

Chincheta no existiría sin **Pinnit**, creada por **[Sasikanth Miriyampalli](https://github.com/msasikanth)**
con diseño de **[Eduardo Pratti](https://twitter.com/edpratti)**. Pinnit se publicó como software libre bajo la
licencia Apache 2.0 y se archivó en 2024 (versión `v1.6.0`). Este repositorio parte de esa versión: toda la base de la
app (crear notas fijadas, recordatorios, temas, la interfaz) es trabajo suyo.

El autor sigue desarrollando una versión nueva y cerrada, **Pinnit 2**, en Google Play. Chincheta **no** es esa app,
no usa su código y no está relacionada con ella. Si te gusta la idea, apoya al autor original.

## Qué añade Chincheta

La Pinnit original solo fijaba notas que escribías tú; el historial estaba sin hacer (`TODO: Complete this for v2`).
Chincheta añade:

- **Historial de notificaciones** de todas las apps, con el icono y el nombre de la app de origen. Se salta las fijas del
  sistema (música, descargas, navegación), los resúmenes de grupo y las repeticiones del mismo texto. En chats guarda el
  último mensaje y, en grupos, quién lo escribió.
- **Fijar desde el historial**: la notificación fijada se ve como la original (icono, nombre de la app, texto completo) y al
  tocarla abre esa app. No tiene botones: solo se desfija o se borra desde dentro de la app.
- **Fijadas que no se pueden quitar**: desde Android 14 el sistema deja deslizar las notificaciones fijas; Chincheta la
  vuelve a poner al momento.
- **Buscar** (sin importar mayúsculas ni tildes), **filtrar por app** y **separadores por día** (Fijadas, Hoy, Ayer…).
- **Pulsación larga**: copiar, compartir, ver solo esa app o dejar de guardar sus notificaciones.
- **Papelera** (restaurar o borrar para siempre; se vacía sola a los 30 días) y **borrar historial** por apps, con deshacer.
- **Apps ignoradas** y **borrado automático** configurable (7, 30, 90 días, 1 año o nunca).
- **Mensajes borrados de WhatsApp**: si el remitente borra un mensaje, se conserva el texto y se marca como
  «borrado por el remitente».
- **Fijar automáticamente** las notificaciones que contengan una palabra o un nombre.
- **Copia de seguridad** a un archivo JSON (historial, fijadas, papelera y ajustes) y restauración.
- **Widget** con las notificaciones fijadas.
- **Bloqueo con huella** (o el PIN del móvil) al abrir la app.

## Privacidad

Todo se guarda **solo en el móvil**. La app no pide permiso de Internet, así que no puede enviar nada a ningún sitio.
Para funcionar necesita:

- **Acceso a notificaciones**, para leer las que llegan.
- **Ver las apps instaladas** (`QUERY_ALL_PACKAGES`), para mostrar su nombre e icono. Por este permiso la app no
  podría publicarse en Google Play tal cual; se distribuye solo aquí.

## Instalar

1. Descarga el APK de la última versión en [Releases](https://github.com/stilton2/chincheta/releases).
2. Instálalo. Si Android marca «ajustes restringidos» y no te deja dar el acceso a notificaciones: Ajustes → Apps →
   Chincheta → menú ⋮ → «Permitir ajustes restringidos».
3. Abre la app y dale **acceso a notificaciones**.
4. Recomendado en Samsung y otros fabricantes: Ajustes → Apps → Chincheta → Batería → **Sin restricciones**, para que el
   sistema no la cierre.

Requiere Android 8.0 o superior.

## Limitaciones conocidas

- **Códigos de verificación**: desde Android 15, el sistema oculta a apps como esta el contenido de las notificaciones con
  códigos de un solo uso; se guardan como «Contenido sensible oculto». No se puede evitar sin permisos de sistema.
- La detección de **mensajes borrados de WhatsApp** depende del texto que pone WhatsApp («Se eliminó este mensaje», etc.)
  y está probada con tests, no con un borrado real.
- Lo que llegó antes de instalar la app no se puede recuperar.

## Cómo se ha hecho

Chincheta la ha hecho [@stilton2](https://github.com/stilton2) junto con **Claude** (el modelo Claude Opus 5.5
de Anthropic, usado a través de **Claude Code**). @stilton2 decidió qué debía hacer la app y la probó en su móvil; Claude
escribió el código, compiló y ejecutó las pruebas. Los commits llevan la línea `Co-Authored-By: Claude`.

### Cómo se ha probado

- **Pruebas automáticas** del proyecto original y nuevas (`./gradlew testDebugUnitTest`), entre ellas
  `RetractedMessagesTest` para la detección de mensajes borrados.
- **Emulador Android 15** (Pixel 6), manejado desde el ordenador con `adb`: llegada de notificaciones con la app abierta y
  en segundo plano, fijar y comprobar que sale como la original, deslizarla y «Borrar todo» (vuelve), buscar sin tildes,
  filtrar, pulsación larga, regla de fijado automático, guardar y restaurar una copia, borrar historial, papelera
  (restaurar y vaciar), apps ignoradas, bloqueo (desbloqueo, 1 minuto de margen, cancelar cierra la app), widget
  (muestra y se actualiza) y reinicio del móvil (las fijadas vuelven solas).
- **Un móvil Samsung con Android 16**: uso real diario, historial, fijar y bloqueo con huella.

## Compilar

Necesitas JDK 17 y el SDK de Android con `platforms;android-34`.

```bash
./gradlew assembleDebug          # versión de pruebas
./gradlew assembleRelease        # sin firmar, salvo que configures tu clave
```

Para firmar la versión release con tu propia clave, crea `~/.android/pinnit-keystore.properties`:

```properties
PINNIT_KEYSTORE_FILE=/ruta/a/tu-clave.jks
PINNIT_KEYSTORE_PASSWORD=...
PINNIT_KEY_ALIAS=...
PINNIT_KEY_PASSWORD=...
```

El código Kotlin sigue en el paquete `dev.sasikanth.pinnit` del original para que sea fácil compararlo con él; el
identificador de la app instalada es `io.github.stilton2.chincheta`.

## Licencia

```
Copyright 2020 Sasikanth Miriyampalli
Modificaciones: copyright 2026 los autores de Chincheta

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

Los cambios respecto a Pinnit `v1.6.0` se resumen en [NOTICE](NOTICE) y se pueden ver uno a uno en el historial de
commits (`git log v1.6.0..`).
