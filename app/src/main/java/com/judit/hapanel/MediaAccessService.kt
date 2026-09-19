package com.judit.hapanel

import android.service.notification.NotificationListenerService

/**
 * Service vide, dont la seule raison d'être est d'obtenir l'accès aux sessions média.
 *
 * Android réserve `MediaSessionManager.getActiveSessions()` aux applications système ou
 * à celles déclarées comme service d'écoute des notifications. On déclare donc ce
 * service, sans rien en faire : c'est le prix à payer pour lire le titre en cours et
 * commander la lecture des applications audio du panneau.
 *
 * L'autorisation s'accorde une fois pour toutes, par ADB :
 * ```
 * adb shell settings put secure enabled_notification_listeners \
 *   com.judit.hapanel/com.judit.hapanel.MediaAccessService
 * ```
 * ou à la main dans Paramètres → Applications → Accès aux notifications.
 */
class MediaAccessService : NotificationListenerService()
