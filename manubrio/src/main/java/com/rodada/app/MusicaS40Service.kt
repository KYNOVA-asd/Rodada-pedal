package com.rodada.app

import android.app.Notification
import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Android exige este acceso para las sesiones multimedia. De las notificaciones,
 * solo lee la de Radio FM para mostrar su frecuencia; no guarda su contenido.
 */
class MusicaS40Service : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        val radio = activeNotifications?.firstOrNull { it.packageName == RADIO_PACKAGE }
        publish(radio)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == RADIO_PACKAGE) publish(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == RADIO_PACKAGE) publish(null)
    }

    override fun onListenerDisconnected() {
        publish(null)
        super.onListenerDisconnected()
    }

    private fun publish(radio: StatusBarNotification?) {
        radioActions = radio?.notification?.actions?.map { it.actionIntent }.orEmpty()
        val title = radio?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val station = title?.takeIf { it.isNotBlank() }?.take(40)
        if (station == currentStation) return
        currentStation = station
        Handler(Looper.getMainLooper()).post { onRadioChanged?.invoke() }
    }

    companion object {
        private const val RADIO_PACKAGE = "com.android.fmradio"
        @Volatile private var radioActions: List<PendingIntent> = emptyList()
        @Volatile var currentStation: String? = null
            private set
        var onRadioChanged: (() -> Unit)? = null

        fun sendRadioAction(zone: MusicWheelView.Zone): Boolean {
            val index = when (zone) {
                MusicWheelView.Zone.PREVIOUS -> 0
                MusicWheelView.Zone.CENTER -> 1
                MusicWheelView.Zone.NEXT -> 2
                else -> return false
            }
            val intent = radioActions.getOrNull(index) ?: return false
            return try {
                intent.send()
                true
            } catch (_: PendingIntent.CanceledException) {
                false
            }
        }
    }
}
