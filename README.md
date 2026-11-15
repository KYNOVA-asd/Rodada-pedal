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
Drive, Google TV, Home, Google Go, Asistente, YouTube, Google One, Gmail y
tres componentes de Facebook.

También quitados (rastreo de fábrica, telemetría y relleno): com.dti.amx
(instalador de Telcel), com.sw.writedateservices, co.sitic.pp, Tencent
Soter, AdServices, OnDevicePersonalization, Feedback, Cronología,
Bienestar digital, Safety Hub, Restore, PartnerSetup, recomendación de
impresión, pruebas de fábrica (zyt.agingtest, sprd.validationtools,
sprd.cameracalibration, sprd.camta, sprd.logmanager), proveedores de
correo/navegador de ejemplo y el salvapantallas básico.
Restaurar uno: adb -s S404823093183 shell cmd package install-existing PAQUETE

Apps conservadas: Play Store, Google Maps, YouTube Music, Servicios de
Google, WebView y el motor de voz de Android.

## Qué hace Rodada

### Fase 1: velocímetro (primera versión)
- Velocidad actual, distancia y tiempo en números grandes.
- Reloj siempre visible.
- Pantalla de alto contraste, legible bajo el sol.
- Sigue midiendo con la pantalla apagada.
- Guarda el recorrido en GPX (pendiente).

En la versión actual, TIEMPO cuenta desde que se abre Rodada. El GPS se
activa solo al iniciar un recorrido con una pulsación larga. La distancia
aceptada por GPS se suma a un kilometraje total guardado en SQLite en el
S40; se consulta y se puede reiniciar con confirmación desde MODE. El reloj
muestra el día, la fecha y la hora.
Cuando la Radio FM del S40 está activa, Rodada muestra su frecuencia desde
la notificación de esa app y usa sus acciones para cambiar de emisora.

### Ya funciona (con Rodada Enlace en el Motorola)
- Pantalla toda en LED azul, con modo día cuando baja el brillo.
- Tres páginas: centro (reloj y velocidad), mapa (indicaciones de
  Google Maps del Motorola) y notificaciones.
- Avisos de las apps elegidas: "te llegó un mensaje de…", y con un
  toque lo lee.
- Franja tipo estéreo de auto con la canción del Motorola o la Radio FM.
- Llamadas: contestar, rechazar, colgar, altavoz y silenciar desde la
  rueda.
- Rueda de música para este teléfono o para el Motorola (Bluetooth HID).
- Voz por el S40 o por el Motorola.
- Buscar el otro teléfono: suena fuerte aunque esté en silencio.
- Audio del Motorola por la bocina o audífonos del S40.
- Se ve encima de la pantalla de bloqueo y gira en cualquier
  orientación.

### Fase 2: voz (pendiente)
- Avisos hablados: velocidad, distancia y promedio.
- Palabra de activación: "Oye Jardiel".
- Comandos:
  - "qué hora es"
  - "a qué velocidad voy"
  - "cuánto llevo"
  - "siguiente" / "anterior" / "pausa" / "sube" / "baja"
  - "contesta" / "cuelga"

### Fase 3: smartwatch (lo que falta)
- Apagar o posponer alarmas por voz.

## Fuera de alcance

- Mapas propios: solo se muestran las indicaciones de Google Maps del
  Motorola.
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
  celular (Rodada Enlace), conectada por Bluetooth.
- Llamadas: el audio queda en el Motorola; el S40 no puede ser manos
  libres. WhatsApp solo deja colgar, no altavoz.
- Audio del Motorola al S40: Android no deja capturar llamadas ni apps
  que bloquean la captura.
- Cancelación de ruido de los audífonos: no hay forma estándar de
  cambiarla desde el S40.
- Batería del Motorola: Rodada Enlace debe estar "Sin restricciones" en
  Ajustes → Apps → Rodada Enlace → Batería.

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

## Estructura del repo

| Carpeta | Qué es | Se instala en |
|---|---|---|
| `manubrio/` | App "Rodada": la pantalla y el control que van en la bici (velocímetro, rueda, voz, franja de canción). | SENWA S40 |
| `enlace/` | App "Rodada Enlace": va en la mochila; recibe órdenes del S40 y le manda notificaciones, llamadas y la canción que suena. | moto g24 power |
| `comun/` | Código compartido: el protocolo de mensajes por Bluetooth y su versión. | (librería de las dos) |
| `docs/` | Reportes y notas, por ejemplo `docs/hid.md`. | — |

## Compilar e instalar

Con los dos teléfonos conectados por USB se elige cada uno por su serial:
S40 = `S404823093183`, moto g24 power = `ZT322NSDX9`.

    gradlew :manubrio:assembleDebug
    adb -s S404823093183 install -r manubrio\build\outputs\apk\debug\manubrio-debug.apk

    gradlew :enlace:assembleDebug
    adb -s ZT322NSDX9 install -r enlace\build\outputs\apk\debug\enlace-debug.apk

Ver errores en vivo:

    adb -s S404823093183 logcat
    adb -s ZT322NSDX9 logcat

## Repositorio

    C:\Users\acouo\Videos\proyectos\Rodada-pedal
