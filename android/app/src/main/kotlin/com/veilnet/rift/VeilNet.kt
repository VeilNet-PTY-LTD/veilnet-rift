package com.veilnet.rift

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import veilnet.Anchor
import veilnet.Veilnet.newAnchor
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

class VeilNet : VpnService() {

    companion object {
        @Volatile
        private var instance: VeilNet? = null
        private const val NOTIFICATION_CHANNEL_ID = "VeilNet"
        private const val NOTIFICATION_ID = 1

        fun stop(){
            instance?.anchorScope?.launch {
                // Stop the tunnel interface
                instance?.egress?.cancel()
                instance?.ingress?.cancel()
                instance?.tunInterface?.close()
                instance?.tunInterface = null
                // Stop the anchor
                instance?.anchor?.stop()
                instance?.anchorScope?.cancel()
                instance?.anchor = null
                // Call stopSelf to stop the service
                instance?.stopSelf()
            }
        }
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var anchor: Anchor? = null
    private var anchorScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var egress: CoroutineScope = CoroutineScope(Dispatchers.IO)
    private var ingress: CoroutineScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onDestroy() {
        // Stop the tunnel interface when the service is destroyed
        egress.cancel()
        ingress.cancel()
        tunInterface?.close()
        tunInterface = null
        // Stop the anchor when the service is destroyed
        anchor?.stop()
        anchorScope.cancel()
        anchor = null
        // Release the instance reference
        instance = null
        super.onDestroy()
    }

    override fun onRevoke() {
        // Stop the tunnel interface when the service is destroyed
        egress.cancel()
        ingress.cancel()
        tunInterface?.close()
        tunInterface = null
        // Stop the anchor when the service is destroyed
        anchor?.stop()
        anchorScope.cancel()
        anchor = null
        // Release the instance reference
        instance = null
        super.onRevoke()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val guardian = intent.getStringExtra("guardian")
        val token = intent.getStringExtra("token")

        if (guardian == null || token == null) {
            Log.e("VeilNet", "Guardian Url or VeilNet token is missing")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        anchorScope.launch {
            try {
                anchor = newAnchor()
            } catch (e: Exception) {
                Log.e("VeilNet", "Failed to create anchor instance")
                stopSelf()
                return@launch
            }

            try {
                anchor!!.start(guardian, token, false)
            } catch (e: Exception) {
                Log.e("VeilNet", "Failed to start anchor")
                stopSelf()
                return@launch
            }

            updateNotification("VeilNet anchor is active, creating tunnel interface...")

            try {
                val cidr = anchor!!.cidr
                val (ip, mask) = cidr!!.split("/")
                val builder = Builder()
                    .setSession("VeilNet")
                    .addAddress(ip, mask.toInt())
                    .addDnsServer("1.1.1.1")
                    .addRoute("0.0.0.0", 0)
                    .setMtu(1500)
                    .addDisallowedApplication(applicationContext.packageName)
                tunInterface = builder.establish()
            } catch (e: Exception) {
                Log.e("VeilNet", "Failed to create tunnel interface")
                stopSelf()
                return@launch
            }

            updateNotification("VeilNet tunnel interface is active, starting egress and ingress...")

            startEgress()
            startIngress()

            updateNotification("VeilNet is active")

            if (anchor?.isAlive == false) {
                stop()
                return@launch
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startEgress() {
        egress.launch {
            try {
                FileInputStream(tunInterface?.fileDescriptor).use { tunnelInput ->
                    val buffer = ByteArray(1500)
                    while (isActive) {  // Check if coroutine is still active
                        try {
                            val size = tunnelInput.read(buffer)
                            if (size > 0) {
                                anchor?.writePacket(buffer, size.toLong())
                            }
                        } catch (e: Exception) {
                            Log.e("VeilNet", "Egress has encountered an error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VeilNet", "Failed to start egress: ${e.message}")
                stopSelf()
            }
        }
    }

    private fun startIngress() {
        ingress.launch {
            try {
                FileOutputStream(tunInterface?.fileDescriptor).use { tunnelOutput ->
                    val buffer = ByteArray(1500)
                    while (isActive) {  // Check if coroutine is still active
                        try {
                            val size = anchor?.readPacket(buffer)
                            if (size != null && size > 0) {
                                tunnelOutput.write(buffer, 0, size.toInt())
                            }
                        } catch (e: Exception) {
                            Log.e("VeilNet", "Ingress has encountered an error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VeilNet", "Failed to start ingress: ${e.message}")
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VeilNet", // User-visible name
                NotificationManager.IMPORTANCE_DEFAULT // Or IMPORTANCE_LOW if less intrusive
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }


    private fun buildNotification(message: String = "VeilNet VPN is active"): Notification {
        // Intent to open your app's main activity when the notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java) // Assuming MainActivity is your entry point
        val pendingIntentFlags =
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            pendingIntentFlags
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("VeilNet VPN")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your notification icon
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Makes the notification non-dismissable by swiping
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Or PRIORITY_LOW
            .build()
    }

    private fun updateNotification(message: String) {
        val notification = buildNotification(message)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
