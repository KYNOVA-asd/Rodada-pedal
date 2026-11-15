package com.rodada.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Una foto con la cámara frontal para el modo guardia, sin vista previa en pantalla.
 * Toma un cuadro de la vista previa (YUV) cuando la exposición ya se asentó y lo
 * guarda como JPEG 640×480 en files/guardia/. Así funciona en cámaras "legacy" como
 * la del S40, donde la foto fija salía negra. alListo recibe el archivo o null.
 */
internal object CamaraFrontal {
    private const val TAG = "RodadaGuardia"
    private const val MIN_AJUSTE_MS = 1_200L
    private const val MAX_AJUSTE_MS = 3_000L
    private const val ANCHO = 640
    private const val ALTO = 480

    @SuppressLint("MissingPermission")
    fun tomarFoto(context: Context, alListo: (File?) -> Unit) {
        val principal = Handler(Looper.getMainLooper())
        val camaras = context.getSystemService(CameraManager::class.java)
        val id = camaras.cameraIdList.firstOrNull {
            camaras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
        if (id == null) {
            alListo(null)
            return
        }
        val hilo = HandlerThread("RodadaCamara").apply { start() }
        val fondo = Handler(hilo.looper)
        val lector = ImageReader.newInstance(ANCHO, ALTO, ImageFormat.YUV_420_888, 3)
        var terminado = false
        var dispositivo: CameraDevice? = null
        // Mismo hilo (fondo) para la cámara y el lector: no hace falta sincronizar.
        var exposicionLista = false
        val inicio = SystemClock.elapsedRealtime()

        fun terminar(archivo: File?) {
            if (terminado) return
            terminado = true
            try { dispositivo?.close() } catch (_: Exception) {}
            lector.close()
            hilo.quitSafely()
            principal.post { alListo(archivo) }
        }

        lector.setOnImageAvailableListener({ r ->
            val imagen = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val pasado = SystemClock.elapsedRealtime() - inicio
                val listo = (exposicionLista && pasado >= MIN_AJUSTE_MS) || pasado >= MAX_AJUSTE_MS
                if (!listo || terminado) return@setOnImageAvailableListener
                val archivo = try {
                    val jpeg = aJpeg(imagen)
                    File(context.filesDir, "guardia").apply { mkdirs() }
                        .resolve("foto_${System.currentTimeMillis()}.jpg")
                        .apply { writeBytes(jpeg) }
                } catch (e: Exception) {
                    Log.w(TAG, "foto falló: ${e.javaClass.simpleName}")
                    null
                }
                terminar(archivo)
            } finally {
                imagen.close()
            }
        }, fondo)

        try {
            camaras.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camara: CameraDevice) {
                    dispositivo = camara
                    @Suppress("DEPRECATION")
                    camara.createCaptureSession(listOf(lector.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(sesion: CameraCaptureSession) {
                            try {
                                val previa = camara.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(lector.surface)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                }
                                sesion.setRepeatingRequest(previa.build(), object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, resultado: TotalCaptureResult) {
                                        val ae = resultado.get(CaptureResult.CONTROL_AE_STATE)
                                        if (ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                                            ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                                        ) exposicionLista = true
                                    }
                                }, fondo)
                            } catch (e: Exception) {
                                terminar(null)
                            }
                        }

                        override fun onConfigureFailed(sesion: CameraCaptureSession) = terminar(null)
                    }, fondo)
                }

                override fun onDisconnected(camara: CameraDevice) = terminar(null)
                override fun onError(camara: CameraDevice, error: Int) = terminar(null)
            }, fondo)
        } catch (e: Exception) {
            Log.w(TAG, "cámara falló: ${e.javaClass.simpleName}")
            terminar(null)
        }
        // Por si la cámara nunca responde.
        fondo.postDelayed({ terminar(null) }, 8_000L)
    }

    /** YUV_420_888 → NV21 → JPEG, respetando los saltos de fila y de píxel. */
    private fun aJpeg(imagen: Image): ByteArray {
        val ancho = imagen.width
        val alto = imagen.height
        val nv21 = ByteArray(ancho * alto * 3 / 2)
        val y = imagen.planes[0]
        var pos = 0
        for (fila in 0 until alto) {
            val base = fila * y.rowStride
            for (col in 0 until ancho) nv21[pos++] = y.buffer.get(base + col * y.pixelStride)
        }
        val u = imagen.planes[1]
        val v = imagen.planes[2]
        for (fila in 0 until alto / 2) {
            for (col in 0 until ancho / 2) {
                nv21[pos++] = v.buffer.get(fila * v.rowStride + col * v.pixelStride)
                nv21[pos++] = u.buffer.get(fila * u.rowStride + col * u.pixelStride)
            }
        }
        val salida = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, ancho, alto, null).compressToJpeg(Rect(0, 0, ancho, alto), 85, salida)
        return salida.toByteArray()
    }
}
