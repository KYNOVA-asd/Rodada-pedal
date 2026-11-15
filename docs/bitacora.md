# Bitácora de rodadas (para reportes)

Cada teléfono guarda sus eventos en su propia base SQLite. Sirve para analizar
las rodadas y calibrar la detección de caídas, la inclinación y el GPS.
Nunca guarda el contenido de notificaciones, mensajes ni indicaciones de Maps:
solo que ocurrieron. Se borra sola lo de más de 30 días.

Código: `comun/src/main/java/com/rodada/enlace/Bitacora.kt`.

## Dónde está

| Teléfono | App | Base |
|---|---|---|
| SENWA S40 (`S404823093183`) | `com.rodada.app` | `databases/rodada_bitacora.db` |
| moto g24 power (`ZT322NSDX9`) | `com.rodada.companion` | `databases/rodada_bitacora.db` |

Además, en el S40 está `databases/rodada_kilometraje.db` con el resumen de cada
recorrido (tabla `recorridos`) y los puntos de encharcamiento
(tabla `encharcamientos`).

## Cómo sacarlas (apps debug, por ADB)

En Git Bash usa `MSYS_NO_PATHCONV=1` para que no cambie las rutas:

    MSYS_NO_PATHCONV=1 adb -s S404823093183 exec-out run-as com.rodada.app cat databases/rodada_bitacora.db > bitacora_s40.db
    MSYS_NO_PATHCONV=1 adb -s ZT322NSDX9 exec-out run-as com.rodada.companion cat databases/rodada_bitacora.db > bitacora_moto.db
    MSYS_NO_PATHCONV=1 adb -s S404823093183 exec-out run-as com.rodada.app cat databases/rodada_kilometraje.db > kilometraje_s40.db

Guarda las copias fuera del repo (no se suben: tienen horarios y ubicaciones).

## Tabla `eventos`

| Columna | Tipo | Qué es |
|---|---|---|
| `id` | INTEGER | Autoincremental |
| `ms` | INTEGER | Hora del evento (epoch en milisegundos; los dos relojes van por red, se pueden cruzar) |
| `tipo` | TEXT | Tipo de evento (abajo) |
| `valor` | REAL | Un número según el tipo (puede ir vacío) |
| `detalle` | TEXT | Texto corto `clave=valor` (máx. 200 caracteres) |

## Eventos del S40 (manubrio)

| tipo | valor | detalle |
|---|---|---|
| `RECORRIDO_INICIO` | — | `bat= temp=` (% y °C del S40) |
| `RECORRIDO_FIN` | km | `seg= max= parones= detenido= bat= temp=` (segundos, km/h, % y °C) |
| `GPS_FIX` | segundos desde el inicio hasta la primera lectura buena (≤ 25 m) | `sat=vistos/usados prec=` |
| `PARON` | — | Se detuvo ≥ 5 s después de moverse |
| `FRENON` | km/h máxima en los 4 s previos | `ahora=` km/h al frenar |
| `INCLINADA` | ángulo en grados respecto a "derecha" (≥ 55° por 3 s) | `lado=IZQ/DER seg_desde_rodar= g=x,y,z ref=x,y,z` (el lado es provisional: hay que calibrarlo con g/ref) |
| `ENDEREZADA` | ángulo | Volvió a menos de 30° |
| `PICO_S40` | fuerza en g (> 2.5 g, máx. uno cada 2 s) | Golpe en el manubrio |
| `TEMP` | °C de la batería | Cada vez que cambia el entero |
| `BATERIA` | % | Cada 5 % |
| `CLIMA` | °C | `llovio= lluvia_en= viento=` |
| `NAV_MANIOBRA` | — | Maps dio una maniobra nueva (sin texto) |
| `NAV_ESTADO` | — | Maps en "Recalculando / Buscando GPS" (no vibra) |
| `ALARMA_CAIDA` | segundos de cuenta | `prueba=` |
| `ESTOY_BIEN` | — | Botón verde de la rueda |
| `AYUDA` | — | Botón rojo de la rueda |
| `AVISO_CAIDA` | 1 si se avisó, 0 si no | — |
| `ENCHARCAMIENTO_AVISO` | metros al punto | — |

## Eventos del moto (enlace)

| tipo | valor | detalle |
|---|---|---|
| `ARMADO` / `DESARMADO` | — | Vigilancia de caídas encendida o apagada (sigue al recorrido); `bat= temp=` del moto |
| `PICO` | fuerza en g (2–3.5 g, máx. uno cada 2 s) | Para calibrar el umbral |
| `GOLPE` | fuerza en g (> 3.5 g) | Golpe fuerte en la mochila |
| `FRENON` | — | Llegó el frenón del S40 |
| `VIGILANDO` | — | Golpe + frenón juntos: empieza a medir quietud |
| `QUIETUD` | movimientos contados en 20 s | `quieto=true/false` (false = no fue caída) |
| `ALARMA_CAIDA` | — | `prueba=` |
| `ESTOY_BIEN` | — | `moto` o `s40` (dónde se apretó) |
| `AYUDA` | — | Pidió ayuda desde el S40 |
| `AVISO` | — | `sms= llamada=` (true/false) |
| `CLIMA_OK` | °C | — |
| `CLIMA_ERROR` | — | `http 401`, etc. (nunca la clave) |

## Reportes útiles

- Por recorrido (entre `RECORRIDO_INICIO` y `RECORRIDO_FIN`): duración,
  km, tiempo hasta `GPS_FIX`, frenones, parones, temperatura máxima y batería
  gastada en los dos teléfonos.
- Calibrar caídas: distribución de `PICO`/`GOLPE` del moto y `PICO_S40`
  durante rodadas normales (para que el umbral no salte con baches), y qué
  pasó en las pruebas de bici tirada (`INCLINADA` con `seg_desde_rodar`,
  `GOLPE`, `FRENON`, `QUIETUD`).
- Calibrar el lado de `INCLINADA`: comparar `g` contra `ref` en pruebas
  donde se sabe de qué lado quedó la bici.
- Maps: cuántos `NAV_ESTADO` (recalculando) por recorrido.
- Clima: aciertos y errores (`CLIMA_OK` / `CLIMA_ERROR`).

## Pruebas pendientes (15/11/2026)

1. Rodar, acostar la bici solo con el S40 (el moto no se mueve) y anotar de
   qué lado quedó.
2. Rodar y acostar la bici con los dos teléfonos tirados.
3. Con esos datos: decidir si la bici acostada dispara la alarma (opción A:
   solo si venía rodando; opción B: siempre durante el recorrido) y
   corregir el lado si sale al revés.
