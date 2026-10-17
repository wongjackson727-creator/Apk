package com.jackson.duostock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

class StockCheckJobService : JobService() {
    companion object {
        const val PREFS = "duo_stock_preferences"
        private const val NOW_ID = 4202
        private const val CHANNEL_ID = "duo_stock_alerts"
        private val running = AtomicBoolean(false)

        fun enable(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
            context.startForegroundService(Intent(context, MinuteMonitorService::class.java))
        }

        fun checkNow(context: Context) {
            if (running.get()) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val job = JobInfo.Builder(NOW_ID, ComponentName(context, StockCheckJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setOverrideDeadline(0L)
                .build()
            scheduler.schedule(job)
        }

        fun disable(context: Context) {
            context.stopService(Intent(context, MinuteMonitorService::class.java))
            context.getSystemService(JobScheduler::class.java).cancel(NOW_ID)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
        }

        fun isEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("enabled", false)

        fun defaultStoreIds(context: Context): String = try {
            val raw = context.assets.open("stores-china-mainland.json").bufferedReader().use { it.readText() }
            val stores = JSONObject(raw).getJSONArray("stores")
            (0 until stores.length()).map { stores.getJSONObject(it).getString("store_number") }.joinToString(",")
        } catch (_: Exception) { "R484,R761,R793,R577,R639" }
    }

    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!running.compareAndSet(false, true)) {
            jobFinished(params, true)
            return false
        }
        worker = Thread {
            try {
                checkInventory()
            } catch (e: Exception) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt("last_error_count", 1)
                    .putString("available_summary", "查詢遇到錯誤：${e.message ?: "未知錯誤"}")
                    .apply()
            } finally {
                running.set(false)
                jobFinished(params, false)
            }
        }.apply { name = "DuoStockCheck"; start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }

    private data class Store(val id: String, val name: String, val city: String)
    private data class Product(val part: String, val name: String)

    private fun checkInventory() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val products = prefs.getString("skus", "").orEmpty().lines().mapNotNull { line ->
            val split = line.split("|", limit = 2)
            if (split.size == 2) Product(split[0].trim(), split[1].trim()) else null
        }
        val selected = prefs.getString("stores", "").orEmpty().split(Regex("[,\\s，]+"))
            .map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        val stores = loadStores().filter { it.id in selected }
        require(products.isNotEmpty()) { "尚未設定商品型號" }
        require(stores.isNotEmpty()) { "門店清單無有效編號" }

        val previous = JSONArray(prefs.getString("available_keys", "[]"))
        val oldAvailable = (0 until previous.length()).map { previous.getString(it) }.toSet()
        val nowAvailable = mutableSetOf<String>()
        val successful = mutableSetOf<String>()
        val errors = mutableListOf<String>()

        for (store in stores) for (product in products) {
            if (Thread.currentThread().isInterrupted) return
            val key = "${product.part}@${store.id}"
            try {
                val available = query(product.part, store.id)
                successful.add(key)
                if (available) {
                    nowAvailable.add(key)
                    if (key !in oldAvailable) {
                        // Notify at the first confirmed store/SKU match instead of waiting for the full nationwide sweep.
                        notifyStock(listOf("${product.name}｜${store.city} ${store.name}（${store.id}）"))
                    }
                }
            } catch (e: Exception) {
                errors.add("$key: ${e.message}")
            }
            try { Thread.sleep(1200) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
        val merged = nowAvailable + (oldAvailable - successful)
        val summary = if (merged.isEmpty()) "目前沒有已確認有貨的門店" else "有貨：" + merged.take(5).joinToString("、") + if (merged.size > 5) " 等 ${merged.size} 個" else ""
        prefs.edit()
            .putString("available_keys", JSONArray(merged.toList()).toString())
            .putString("last_checked", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(Instant.now()))
            .putInt("last_error_count", errors.size)
            .putString("available_summary", summary)
            .apply()
    }

    private fun query(part: String, store: String): Boolean {
        val params = listOf(
            "pl" to "true", "mts.0" to "regular", "mts.1" to "compact",
            "cppart" to "UNLOCKED/CH", "parts.0" to part, "store" to store
        ).joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val connection = (URL("https://www.apple.com.cn/shop/retail/pickup-message?$params").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Referer", "https://www.apple.com.cn/shop/buy-iphone/")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/132 Mobile Safari/537.36")
        }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("Apple HTTP ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val stores = json.optJSONObject("body")?.optJSONArray("stores") ?: JSONArray()
            for (i in 0 until stores.length()) {
                val item = stores.getJSONObject(i)
                if (item.optString("storeNumber") == store) {
                    val availability = item.optJSONObject("partsAvailability")?.optJSONObject(part)
                    return availability?.optString("pickupDisplay") == "available"
                }
            }
            return false
        } finally { connection.disconnect() }
    }

    private fun loadStores(): List<Store> {
        val raw = assets.open("stores-china-mainland.json").bufferedReader().use { it.readText() }
        val array = JSONObject(raw).getJSONArray("stores")
        return (0 until array.length()).map {
            val item = array.getJSONObject(it)
            Store(item.getString("store_number"), item.optString("name", item.getString("store_number")), item.optString("city", ""))
        }
    }

    private fun notifyStock(items: List<String>) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "iPhone Duo 有貨通知", NotificationManager.IMPORTANCE_HIGH)
        )
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = items.take(5).joinToString("\n") + if (items.size > 5) "\n另有 ${items.size - 5} 項" else ""
        val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        notification.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("iPhone Duo 門店取貨有貨")
            .setContentText(items.first())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
        manager.notify(1024, notification.build())
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
