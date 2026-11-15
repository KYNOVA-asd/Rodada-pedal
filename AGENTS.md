# AGENTS.md

Instrucciones para agentes de código que trabajen en este repo.
El contexto del producto está en README.md; léelo antes de empezar.

## Proyecto

Rodada: dos apps Android nativas.
- Rodada (S40): computadora de bici en el manubrio. Velocidad por GPS,
  hora, rueda de música, avisos por voz.
- Rodada Enlace (Motorola): va en la mochila; recibe órdenes del S40 y
  le manda notificaciones, llamadas y la canción que suena.

## Estructura del repo

- manubrio/: app del S40 ("Rodada"). Paquete com.rodada.app (se
  conserva para que el S40 actualice sin desinstalar).
- enlace/: app del Motorola ("Rodada Enlace"). Paquete
  com.rodada.companion.
- comun/: librería compartida por las dos: el protocolo Bluetooth
  (com.rodada.enlace.Enlace, Mensaje, CanalEnlace) y su versión.
- docs/: reportes y notas (por ejemplo docs/hid.md).

## Dispositivos

| Teléfono | App | Serial ADB | Sistema |
|---|---|---|---|
| SENWA S40 | manubrio | S404823093183 | Android 13 Go (API 33), armeabi-v7a, 2 GB, 1500 mAh, 4" |
| moto g24 power | enlace | ZT322NSDX9 | Android 14 (API 34), arm64-v8a |

- Los dos se conectan por USB con ADB. Con los dos conectados usa
  siempre adb -s SERIAL.
- S40: sin Chrome ni Asistente de Google; no dependas de ellos.
  Conserva Servicios de Google, WebView y el motor de voz.
- Si el Motorola no aparece en adb, revisa que spacedesk (en la laptop)
  no lo tenga en modo accesorio USB.

## Entorno

- Windows, Acer Swift 3 con 8 GB de RAM.
- Microsoft OpenJDK 17, Android SDK Command Line Tools, VS Code.
- JAVA_HOME, ANDROID_HOME y PATH ya configurados.
- NO instales Android Studio ni emuladores.
- NO instales nada fuera del repo sin preguntar antes.

## Stack

- Kotlin, vistas XML simples o Compose mínimo; elige lo más ligero.
- Gradle con wrapper (gradlew) incluido en el repo.
- manubrio: minSdk 33. enlace: minSdk 34. comun: minSdk 29.
  compileSdk y targetSdk: la plataforma instalada en el SDK (36).
- Protocolo (Bluetooth clásico RFCOMM, mensajes de texto): documentado
  en comun/src/main/java/com/rodada/enlace/Enlace.kt. Si cambias el
  formato, sube Enlace.VERSION. El Motorola escucha; el S40 se conecta.

## Comandos

Compilar cada app:

    gradlew :manubrio:assembleDebug
    gradlew :enlace:assembleDebug

Instalar Rodada en el S40:

    adb -s S404823093183 install -r manubrio\build\outputs\apk\debug\manubrio-debug.apk

Instalar Rodada Enlace en el Motorola:

    adb -s ZT322NSDX9 install -r enlace\build\outputs\apk\debug\enlace-debug.apk

Ver registros:

    adb -s S404823093183 logcat
    adb -s ZT322NSDX9 logcat

Después de cada cambio, compila y confirma que termina sin errores
antes de dar la tarea por terminada.

## Reglas de diseño

- Ligereza primero: 2 GB de RAM y Android Go. Evita librerías pesadas
  y cada dependencia nueva debe justificarse.
- Batería primero: 1500 mAh. Pide ubicación solo mientras hay un
  recorrido activo y suelta GPS, micrófono y wakelocks al terminar.
- Todo debe funcionar sin conexión a internet. El clima es un extra:
  si no hay internet o falla la consulta, lo demás sigue igual.
- GPS con pantalla apagada: usa un servicio en primer plano con
  notificación persistente.
- Voz: usa el TextToSpeech del sistema. No incluyas voces ni motores
  de voz comerciales dentro de la app.
- Pantalla: números muy grandes, alto contraste, legible bajo el sol,
  botones amplios para usar con guantes. Mantén la pantalla encendida
  durante el recorrido.
- Textos de la interfaz en español, en strings.xml.
- Pide permisos en el momento en que se necesitan, con explicación.

## Lo que ya existe

### Rodada (S40, manubrio/)

- Pantalla toda en LED azul (LedTextView, LedTickerView, NavArrowView);
  la rueda y los botones no son LED. Modo día: con el brillo bajo los
  paneles cambian a fondo claro (setBacklit); con brillo alto, fondo
  oscuro iluminado.
- Tres páginas con deslizar: centro (reloj, velocidad, distancia,
  tiempo); dedo de izquierda a derecha = MAPA (indicaciones de Google
  Maps del Motorola; un toque abre Maps en el S40); de derecha a
  izquierda = NOTIFICACIONES.
- Gestos: toque = hora / leer aviso; doble toque = repetir el último
  aviso; deslizar arriba = descartar; pulsación larga = iniciar o
  detener recorrido. Insignia ✉ con avisos sin leer (últimos 5).
- Brillo: baja a los 10 s sin tocar y sube con un toque, un aviso o
  una maniobra nueva. Con brillo bajo se despierta en ACTION_UP (al
  cambiar el brillo en ACTION_DOWN se pierde el gesto).
- Gira en las cuatro orientaciones (fullSensor + configChanges); en
  horizontal la pantalla y la rueda van lado a lado.
- Se muestra encima de la pantalla de bloqueo (showWhenLocked).
- Velocímetro (RideService, RideState): servicio en primer plano con GPS
  solo durante el recorrido. Kilometraje total persistente
  (OdometerStore), se consulta y reinicia desde MODE. Exportar GPX:
  pendiente.
- Menú MODE (dialog_mode): destino de la rueda, voz y kilometraje.
- Franja LED (LedTickerView): alterna reloj y "lo que suena" como un
  estéreo de auto; también muestra la Radio FM del S40 (MusicaS40Service
  lee su notificación y usa sus acciones para cambiar de emisora).
- Rueda (MusicWheelView): cinco zonas (arriba volumen +, abajo
  volumen −, izquierda anterior, derecha siguiente, centro
  reproducir/pausa). Cada zona vibra. Log por zona: RodadaRueda.
  En llamada cambia a botones (CallMode): sonando = ✕ rechazar a la
  izquierda y ✓ contestar a la derecha; en curso = ✕ colgar, altavoz al
  centro y silenciar a la derecha (solo si la notificación los trae).
- Destino de la rueda (menú ☰): "este teléfono" (teclas multimedia del
  sistema) o "Motorola" por Bluetooth HID (HidMediaRemote, docs/hid.md;
  descriptor solo Consumer Control). El HID se registra solo con destino
  Motorola y se suelta con unregisterApp(). En el S40 registerApp() puede
  devolver false y aun así registrar; se decide por onAppStatusChanged.
  Log: RodadaHid.
- Voz (SpeechController): TTS del sistema en español con foco de audio
  temporal. "Voz en: este teléfono / Motorola" en el menú ☰ (en el
  Motorola habla Rodada Enlace, por sus audífonos).
- Buscar teléfono (comun/Timbre): menú ☰ "Buscar Motorola"; y si el
  Motorola busca al S40, suena la alarma 30 s y enciende la pantalla;
  cualquier toque la detiene.
- Audio del Motorola en el S40 (AudioBridgeReceiver): botón de audio;
  recibe AAC por un canal RFCOMM aparte (PuenteAudio) y lo reproduce en
  la bocina o audífonos del S40. Mientras está activo apaga la Radio FM
  y la rueda sube/baja el volumen del S40.

### Rodada Enlace (Motorola, enlace/)

- EnlaceService: servidor RFCOMM (el S40 se conecta, reintento 3/10/30/60
  s y luego por eventos). Al conectar manda la canción, la navegación y
  la llamada actuales.
- AvisosService (NotificationListener): reenvía solo las apps elegidas
  (AppsActivity, por defecto ninguna), las indicaciones de Google Maps
  y las llamadas (CallStyle). Aprieta los botones de la notificación de
  la llamada (contestar, rechazar, colgar, altavoz, silenciar) por
  palabras clave. No guarda contenido; deduplica con hash en memoria.
- SesionMusica (comun): canción que suena en el Motorola para la franja.
- Voz: dice en el Motorola lo que pide el S40 (DECIR) y pausa la música
  mientras habla.
- Buscar S40 y "TE ESTÁN BUSCANDO": aviso rojo a pantalla completa,
  también sobre el bloqueo.
- AudioBridgeService: captura el audio multimedia permitido
  (MediaProjection, pide permiso cada vez) y lo manda al S40 en AAC 24
  kHz mono. Android no deja capturar llamadas ni apps que lo bloquean.

### Límites ya comprobados

- El S40 no puede saber la canción del Motorola por HID (no tiene AVRCP
  Controller); por eso la manda Rodada Enlace.
- El S40 no puede ser manos libres del Motorola (sin HFP-HF); el audio
  de las llamadas queda en el Motorola.
- La notificación de llamada de WhatsApp solo trae "Colgar".
- El Motorola oculta Log.d de los avisos salvo con
  adb shell setprop log.tag.RodadaAvisos D.

## Fases

Trabaja solo en la fase actual. No adelantes las siguientes.

1. Velocímetro (ACTUAL): velocidad, distancia, tiempo, reloj, servicio
   en primer plano y exportar GPX.
2. Voz: avisos hablados, palabra de activación "Oye Jardiel", comandos
   de hora, velocidad, música y llamadas.
3. Smartwatch: mostrar y leer notificaciones, manejar alarmas.

## Fuera de alcance

- Mapas propios: el S40 solo muestra las indicaciones de Google Maps del
  Motorola (texto y flecha), no dibuja mapas.
- Reproductor de música propio (se controla YouTube Music con teclas
  multimedia del sistema).

## Forma de trabajar

- Cambios pequeños, un commit por cambio, mensajes en español.
- No borres ni reescribas archivos que no sean parte de la tarea.
- No subas al repo claves, keystores ni local.properties. La clave del
  clima va en .env (api_clima=...), ignorado por git; enlace la lee al
  compilar (BuildConfig.API_CLIMA). Nunca la escribas en logs.
- Enlace: lo único que sale a internet es la consulta del clima a
  OpenWeatherMap (Clima.kt): al iniciar un recorrido, cada hora mientras
  dure, o con tres toques en el S40; ubicación redondeada a ~1 km y
  respuesta reusada si tiene menos de 30 min.
- Manubrio (S40): lo único que sale a internet son las alertas del modo
  guardia por Telegram (Telegram.kt), por Wi-Fi, solo cuando mueven la
  bici o al probarlo. El token va en .env (telegram_bot=..., opcional
  telegram_chat=...). Nada más sale a internet.
  No guardes el contenido de las notificaciones en disco ni en logs.
  Sin sondeos constantes.
- Si algo no se puede hacer en Android 13 Go o gasta mucha batería,
  dilo y propone una alternativa en lugar de forzarlo.
- Si una decisión no está en README.md ni aquí, pregunta.
