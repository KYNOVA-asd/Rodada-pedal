# Rodada

App Android para usar un SENWA S40 como computadora de bici y "smartwatch
de manubrio": velocidad por GPS, hora, control por voz y notificaciones,
sin tocar la pantalla ni quitarse el casco.

## El problema

- El celular principal va expuesto al sol en el manubrio.
- "Ok Google" no responde en ruta, ni para algo tan simple como la hora.
- Cambiar de canción obliga a ver la pantalla o quitarse el casco: los
  botones de los audífonos quedan debajo del casco.
- Contestar llamadas o apagar una alarma en movimiento es difícil.
- El intercomunicador de casco sí tiene botones, pero no se puede llevar
  puesto fuera de la bici; los audífonos sí.
- Zepp Life va lentísimo en el S40 y solo se necesita ver la velocidad.

## Dispositivo

| Dato           | Valor                         |
|----------------|-------------------------------|
| Modelo         | SENWA S40                     |
| Sistema        | Android 13 Go                 |
| RAM            | 2 GB                          |
| Almacenamiento | 32 GB                         |
| Pantalla       | 4"                            |
| Batería        | 1500 mAh                      |
| Sensores       | GPS                           |

Apps quitadas por ADB del usuario principal (restaurables): Chrome, Meet,
Drive, Google TV, Home, Google Go, Asistente, YouTube, Google One y tres
componentes de Facebook.

Apps conservadas: Play Store, Google Maps, YouTube Music, Servicios de
Google, WebView y el motor de voz de Android.

## Qué hace Rodada

### Fase 1: velocímetro (primera versión)
- Velocidad actual, distancia y tiempo en números grandes.
- Reloj siempre visible.
- Pantalla de alto contraste, legible bajo el sol.
- Sigue midiendo con la pantalla apagada.
- Guarda el recorrido en GPX.

### Fase 2: voz
- Avisos hablados: hora, velocidad, distancia y promedio.
- Palabra de activación: "Oye Jardiel".
- Comandos:
  - "qué hora es"
  - "a qué velocidad voy"
  - "cuánto llevo"
  - "siguiente" / "anterior" / "pausa" / "sube" / "baja"
  - "contesta" / "cuelga"

### Fase 3: smartwatch
- Mostrar en pantalla las notificaciones del teléfono.
- Leer en voz alta quién llama o escribe.
- Apagar o posponer alarmas por voz.

## Fuera de alcance

- Navegación con mapas: se usa Organic Maps, sin conexión.
- Reproductor de música: se controla YouTube Music, no se reemplaza.

## Límites conocidos

- Voz: Loquendo es comercial y no se puede incluir. Rodada usa el motor
  de voz instalado en Android; la voz se cambia desde el sistema.
- Batería: escuchar la palabra de activación todo el tiempo consume.
  Hay que medirlo en una salida corta antes de rutas largas.
- Micrófono: el viento afecta el reconocimiento. El micrófono Bluetooth
  baja la calidad de la música mientras está activo.
- Alarmas: apagar la alarma de otra app no siempre es posible; por
  investigar.
- Notificaciones de otro celular: requieren una app compañera en ese
  celular, conectada por Bluetooth.

## Decisiones pendientes

- ¿El S40 lleva la SIM, la música y los audífonos, o es solo pantalla
  y el celular principal va guardado?
- ¿Micrófono del S40 o de los audífonos para los comandos?
- ¿Reconocimiento de voz sin conexión (Vosk) u otro motor?

## Entorno de desarrollo

- Laptop: Acer Swift 3, 8 GB de RAM, Windows.
- Editor: VS Code.
- Microsoft OpenJDK 17.0.21.
- Android SDK Command Line Tools 22.0.
- Variables configuradas: JAVA_HOME, ANDROID_HOME y PATH.
- Sin Android Studio y sin emulador: se prueba en el S40 por USB.
- Lenguaje: Kotlin nativo.

## Compilar e instalar

    gradlew assembleDebug
    adb install -r app\build\outputs\apk\debug\app-debug.apk

Ver errores en vivo:

    adb logcat

## Repositorio

    C:\Users\acouo\Videos\proyectos\Rodada-pedal
