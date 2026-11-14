# Reporte: el S40 como control Bluetooth HID

Investigación del 14 de noviembre de 2026: ¿puede el S40 presentarse ante otro
teléfono como control multimedia Bluetooth (perfil HID Device) sin instalar
nada en ese teléfono?

## Resultado de la prueba (14 de noviembre de 2026)

- **Funciona.** El S40 se empareja desde los Ajustes de Bluetooth del moto g24
  power (`40:FA:FE:E6:B0:81`) y este responde a reproducir/pausa, siguiente,
  anterior y volumen. Implementado en `manubrio/.../HidMediaRemote.kt`.
- En el S40, `registerApp()` puede devolver `false` y aun así registrar; se
  decide por `onAppStatusChanged`.
- Se reconecta solo al reabrir Rodada (unos 4 s).
- HID es de un solo sentido: el S40 no puede saber qué suena en el Motorola.
  Para eso y para notificaciones se hizo la app Rodada Enlace (módulo enlace).

## Respuesta original: **soportado** a nivel de sistema en el S40

Lo que falta confirmar es si el Motorola acepta al S40 y responde a las teclas. Eso no se ve con comandos de solo lectura; se sabe con la prueba mínima de abajo.

### Qué revisé (todo de solo lectura, sin instalar nada ni tocar la app)

Nota: `adb` no está en el PATH de esta terminal. Usé `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.

| Comprobación | Resultado |
|---|---|
| `getprop bluetooth.profile.hid.device.enabled` | **true** |
| `dumpsys bluetooth_manager` → "Enabled Profile Services" | **HidDeviceService** activo (también HidHost, A2DP Source, AVRCP Target) |
| Sección `Profile: HidDeviceService` | Vacía. Es lo normal: ninguna app se ha registrado como HID todavía |
| `pm list features` | `android.hardware.bluetooth`, `bluetooth_le` |
| Chip y sistema | Spreadtrum SC7731E, API 33, `low_ram=true`, solo 32 bits |
| Dispositivos emparejados del S40 | Ninguno. Los audífonos parecen ir con el Motorola |

**Por qué no basta con AVRCP:** el S40 tiene A2DP Sink desactivado y AVRCP solo como Target. O sea, puede ser controlado, pero no puede controlar a otro teléfono por esa vía. Por eso HID Device es el camino correcto.

**Riesgo principal, que solo resuelve la prueba:** algunos fabricantes con chips Spreadtrum o MediaTek dejan el servicio activo pero `registerApp()` falla, o el otro teléfono se desconecta enseguida. No hay forma de saberlo sin intentarlo.

---

### Plan de prueba mínima (sin implementar)

**Ojo con las fases:** el control de música es de la fase 2 y la actual es la 1. Esto sería un experimento aislado, solo si tú lo apruebas.

**Archivos**
- **Nuevo:** `app/src/main/java/com/rodada/app/HidMediaRemote.kt`, unas 150 líneas. Se encarga de:
  - obtener el proxy con `getProfileProxy(..., BluetoothProfile.HID_DEVICE)`;
  - registrarse con `registerApp(...)`;
  - manejar los callbacks;
  - enviar las teclas con `sendKey(usage)`.
- **Cambios:**
  - `AndroidManifest.xml`: permisos.
  - `strings.xml`: textos de permiso y estado.
  - `MainActivity.kt`: un botón temporal "Probar ⏯". Se puede conectar a la rueda después, cuando Codex termine.

**Descriptor:** solo la página Consumer Control, sin teclado. Así Android lo trata como un teclado "no alfabético" y no oculta el teclado en pantalla del Motorola.
```
05 0C 09 01 A1 01 85 01 15 00 26 FF 03 19 00 2A FF 03 75 10 95 01 81 00 C0
```
- Cada tecla se manda como un reporte con ID 1 y el código de 16 bits en little-endian, seguido de otro reporte `00 00` para soltarla.
- Códigos de las teclas:

| Tecla | Código |
|---|---|
| Reproducir/pausa | `0xCD` |
| Siguiente | `0xB5` |
| Anterior | `0xB6` |
| Volumen + | `0xE9` |
| Volumen − | `0xEA` |

- El Motorola los traduce solo a `KEYCODE_MEDIA_*` y `VOLUME_*`, sin instalarle nada.

**Permisos en Android 13**
- `BLUETOOTH_CONNECT`: es de tiempo de ejecución ("Dispositivos cercanos"). Se pide al tocar el botón de prueba, con una explicación.
- `BLUETOOTH_ADVERTISE`: hace falta para que el S40 sea visible durante el emparejamiento.
- `BLUETOOTH_SCAN`: no hace falta si el emparejamiento se inicia desde el Motorola.
- En API 33 no hace falta un tipo nuevo de servicio en primer plano. En API 34 o superior sí haría falta `connectedDevice`.

**Pasos de la prueba**
1. Registrar la app y esperar `onAppStatusChanged(registered=true)`. Si falla aquí, la respuesta es "no soportado en la práctica".
2. Hacer visible el S40 y emparejarlo desde los Ajustes de Bluetooth del Motorola. Debería aparecer como dispositivo de entrada.
3. Con YouTube Music sonando en el Motorola, mandar reproducir/pausa y luego volumen.
4. Revisar con `adb logcat -s BluetoothHidDevice*` en el S40.

**Riesgos**
- **Reconexión:**
  - El registro HID se pierde si muere el proceso de Rodada. Hay que registrarse dentro de `RideService`, solo durante el recorrido, y luego llamar a `connect(motorola)`.
  - Puede que el Motorola no se reconecte solo. Hay que probarlo apagando y prendiendo el Bluetooth.
  - Solo una app a la vez puede estar registrada como HID en el S40.
- **Batería:**
  - Un enlace HID clásico en reposo gasta poco porque el Bluetooth entra en modo de bajo consumo, mucho menos que el GPS.
  - Hay que soltarlo con `unregisterApp()` al terminar el recorrido, como pide AGENTS.md.
- **Audífonos al mismo tiempo:**
  - Si los audífonos van con el Motorola, este tendría dos enlaces: A2DP con los audífonos y HID con el S40. Eso suele funcionar bien.
  - El chip de radio del Motorola comparte la antena, así que podría haber cortes breves de audio al mandar teclas. Hay que probarlo en ruta.
  - Si algún día los audífonos van con el S40, sería A2DP Source y HID Device a la vez en un chip modesto. Es más arriesgado.
- **Teclado físico:** si el descriptor incluyera teclas de teclado, el Motorola podría ocultar su teclado en pantalla. Por eso va solo Consumer Control.

---

### Alternativa si la prueba falla: app compañera en el Motorola

- **En el S40:** un cliente RFCOMM (`BluetoothSocket`) que manda comandos cortos, como `"PLAY"` o `"NEXT"`.
- **En el Motorola:**
  - una segunda app con un servicio en primer plano siempre escuchando en un `BluetoothServerSocket`;
  - recibe los comandos y los ejecuta con `AudioManager.dispatchMediaKeyEvent()` y `adjustStreamVolume()`;
  - en Android 14 o superior, el Motorola probablemente exija el tipo de servicio `connectedDevice`.
- **Tamaño:** de 3 a 5 veces el trabajo de la prueba HID:
  - dos apps y un protocolo propio;
  - un segundo módulo o repo, que AGENTS.md no contempla (dice "un solo módulo"), así que eso habría que decidirlo;
  - reconexión en los dos lados;
  - las restricciones de batería de Motorola, que matan servicios en segundo plano;
  - instalar y mantener esa app.
- **Ventaja:** el README ya dice que la fase 3 (notificaciones del otro teléfono) va a necesitar esa app compañera. Ese trabajo se aprovecharía después.

**Recomendación:** primero la prueba HID, que es corta y no toca nada del Motorola. Si `registerApp` falla o el Motorola no responde, pasar a la app compañera y diseñarla pensando también en la fase 3.
