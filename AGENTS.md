# AGENTS.md

Instrucciones para agentes de código que trabajen en este repo.
El contexto del producto está en README.md; léelo antes de empezar.

## Proyecto

Rodada: app Android nativa para usar un SENWA S40 como computadora de
bici en el manubrio. Velocidad por GPS, hora, avisos por voz y, más
adelante, comandos de voz y notificaciones.

## Dispositivo objetivo

- SENWA S40, Android 13 Go (API 33), 2 GB de RAM, pantalla de 4",
  batería de 1500 mAh.
- Es el único dispositivo de pruebas. Conectado por USB con ADB.
- Sin Chrome ni Asistente de Google: no dependas de ellos.
- Conserva Servicios de Google, WebView y el motor de voz de Android.

## Entorno

- Windows, Acer Swift 3 con 8 GB de RAM.
- Microsoft OpenJDK 17, Android SDK Command Line Tools, VS Code.
- JAVA_HOME, ANDROID_HOME y PATH ya configurados.
- NO instales Android Studio ni emuladores.
- NO instales nada fuera del repo sin preguntar antes.

## Stack

- Kotlin, vistas XML simples o Compose mínimo; elige lo más ligero.
- Gradle con wrapper (gradlew) incluido en el repo.
- minSdk 33. compileSdk y targetSdk: la plataforma instalada en el SDK.
- Un solo módulo: app.
- Paquete: com.rodada.app

## Comandos

Compilar:

    gradlew assembleDebug

Instalar en el S40:

    adb install -r app\build\outputs\apk\debug\app-debug.apk

Ver registros:

    adb logcat

Después de cada cambio, compila y confirma que termina sin errores
antes de dar la tarea por terminada.

## Reglas de diseño

- Ligereza primero: 2 GB de RAM y Android Go. Evita librerías pesadas
  y cada dependencia nueva debe justificarse.
- Batería primero: 1500 mAh. Pide ubicación solo mientras hay un
  recorrido activo y suelta GPS, micrófono y wakelocks al terminar.
- Todo debe funcionar sin conexión a internet.
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

- Velocímetro (RideService, RideState): servicio en primer plano con GPS
  solo durante el recorrido; velocidad, distancia, tiempo y reloj en
  números grandes. Mantener pulsado el panel de arriba inicia o detiene
  el recorrido. Exportar GPX: pendiente.
- Rueda de música (MusicWheelView): cinco zonas (arriba volumen +,
  abajo volumen −, izquierda anterior, derecha siguiente, centro
  reproducir/pausa). Cada zona vibra. En este teléfono usa teclas
  multimedia del sistema y muestra el indicador de volumen de Android.
  Log por zona con la etiqueta RodadaRueda.
- Voz (SpeechController): TextToSpeech del sistema con voz en español
  instalada. Un toque en el panel de arriba dice la hora y, durante un
  recorrido, la velocidad. Pide foco de audio temporal y lo suelta al
  terminar de hablar.
- Control del Motorola por Bluetooth HID (HidMediaRemote): el S40 se
  presenta como control multimedia (descriptor solo Consumer Control,
  sin teclado) y manda las cinco teclas de la rueda al otro teléfono,
  sin instalarle nada. Probado y funcionando con YouTube Music en el
  Motorola. Log con la etiqueta RodadaHid.
  - Menú ☰ (esquina de la rueda): "Rueda: este teléfono" o
    "Rueda: Motorola". El destino se guarda en SharedPreferences.
  - El HID se registra solo mientras el destino es Motorola y se suelta
    con unregisterApp() al cambiar de destino o cerrar la app.
  - Indicador pequeño abajo a la izquierda (verde conectado, ámbar
    reconectando, rojo error). Si se desconecta, reintenta cada 15 s.
  - Emparejamiento la primera vez: desde Ajustes de Bluetooth del
    Motorola, con el S40 visible ("S40").
  - En el S40, registerApp() puede devolver false y aun así registrar;
    se decide por onAppStatusChanged.
  - Con HID no se puede saber qué canción suena en el Motorola (es de
    un solo sentido; el S40 no tiene AVRCP Controller). Mostrar la
    canción en pantalla solo es posible con destino "este teléfono" o
    con una app compañera.

## Fases

Trabaja solo en la fase actual. No adelantes las siguientes.

1. Velocímetro (ACTUAL): velocidad, distancia, tiempo, reloj, servicio
   en primer plano y exportar GPX.
2. Voz: avisos hablados, palabra de activación "Oye Jardiel", comandos
   de hora, velocidad, música y llamadas.
3. Smartwatch: mostrar y leer notificaciones, manejar alarmas.

## Fuera de alcance

- Navegación con mapas (se usa Organic Maps).
- Reproductor de música propio (se controla YouTube Music con teclas
  multimedia del sistema).

## Forma de trabajar

- Cambios pequeños, un commit por cambio, mensajes en español.
- No borres ni reescribas archivos que no sean parte de la tarea.
- No subas al repo claves, keystores ni local.properties.
- Si algo no se puede hacer en Android 13 Go o gasta mucha batería,
  dilo y propone una alternativa en lugar de forzarlo.
- Si una decisión no está en README.md ni aquí, pregunta.
