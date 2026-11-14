package com.rodada.app

import android.service.notification.NotificationListenerService

/**
 * No lee notificaciones: existe solo porque Android exige un
 * NotificationListenerService con acceso concedido para leer la sesión de
 * música activa del S40 (franja con destino "este teléfono").
 */
class MusicaS40Service : NotificationListenerService()
