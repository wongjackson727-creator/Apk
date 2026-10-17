package com.jackson.duostock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class MinuteMonitorService : Service() {
    companion object {
        private const val CHANNEL_ID = "duo_stock_monitor"
        private const val NOTIFICATION_ID = 2048
        private const val INTERVAL_MS = 60_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val checkLoop = object : Runnable {
        override fun run() {
            if (!StockCheckJobService.isEnabled(this@MinuteMonitorService)) {
                stopSelf()
                return
            }
            StockCheckJobService.checkNow(this@MinuteMonitorService)
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(checkLoop)
        handler.post(checkLoop)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkLoop)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "iPhone Duo 每分鐘庫存監測",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "監測啟用時顯示此常駐通知" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("iPhone Duo 庫存監測中")
            .setContentText("約每分鐘查詢已選取的 Apple 門店")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }
}
