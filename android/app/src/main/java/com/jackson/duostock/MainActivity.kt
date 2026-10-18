package com.jackson.duostock

import android.Manifest
import android.app.Activity
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(StockCheckJobService.PREFS, Context.MODE_PRIVATE) }
    private lateinit var skuField: EditText
    private lateinit var storeField: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        render()
    }

    private fun render() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFFF7F8FA.toInt())
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        val title = TextView(this).apply {
            text = "iPhone Duo 庫存監測"
            textSize = 25f
            setTextColor(0xFF121820.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)
        root.addView(label("約每分鐘啟動一輪 Apple 中國大陸門店取貨查詢。持續監測時會顯示 Android 通知；發現無貨轉有貨時會另發到貨通知。", 15f, 0xFF4D5968.toInt()))
        root.addView(button("登入 Apple 帳號（預設瀏覽器）") {
            openInDefaultBrowser("https://account.apple.com/")
        }, marginTop(8))
        root.addView(button("前往 Apple 官網確認並下單") {
            openSelectedProductPage()
        }, marginTop(8))
        root.addView(label("到貨後請由你本人選擇門店、登入並確認下單和付款。App 不會自動送出訂單，也不能替你保留庫存。", 13f, 0xFF687386.toInt()), marginTop(8))
        root.addView(label("商品型號", 18f, 0xFF121820.toInt(), true))
        root.addView(label("每行一個中國版 Apple 型號代碼和名稱，格式：代碼 | 容量／顏色。請將所有想監測的容量與顏色都加入。", 13f, 0xFF596575.toInt()))
        skuField = EditText(this).apply {
            hint = "例如：MXXXXCH/A | Duo 512GB 星光白"
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP
            textSize = 14f
            setText(prefs.getString("skus", ""))
            setPadding(18, 14, 18, 14)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(skuField, marginTop(8))

        root.addView(label("監測門店", 18f, 0xFF121820.toInt(), true), marginTop(18))
        root.addView(label("預設為門店清單中的 48 家；可刪除不需要的編號。要加入新店，請先核對 Apple 官方門店編號。以逗號或空格分隔。", 13f, 0xFF596575.toInt()))
        storeField = EditText(this).apply {
            minLines = 3
            maxLines = 5
            textSize = 13f
            gravity = Gravity.TOP
            setText(prefs.getString("stores", StockCheckJobService.defaultStoreIds(this@MainActivity)))
            setPadding(18, 14, 18, 14)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(storeField, marginTop(8))

        val saveButton = button("儲存設定並啟用每分鐘監測") {
            if (saveAndValidate()) {
                StockCheckJobService.enable(this)
                Toast.makeText(this, "已啟用每分鐘監測；持續期間會顯示通知", Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }
        root.addView(saveButton, marginTop(16))
        root.addView(button("立即查詢一次") {
            if (saveAndValidate()) {
                StockCheckJobService.checkNow(this)
                Toast.makeText(this, "已排入查詢；完成後會更新通知", Toast.LENGTH_SHORT).show()
            }
        }, marginTop(8))
        root.addView(button("停止背景監測") {
            StockCheckJobService.disable(this)
            Toast.makeText(this, "已停止背景監測", Toast.LENGTH_SHORT).show()
            updateStatus()
        }, marginTop(8))
        status = label("", 14f, 0xFF334155.toInt())
        root.addView(status, marginTop(18))
        root.addView(label("提示：每分鐘監測需要保留常駐通知；Android 省電模式、網路狀態或廠商背景限制仍可能延後或停止查詢。每輪會按序查詢各門店，店數或 SKU 較多時一輪可能超過一分鐘。庫存由 Apple 官網系統提供，請在收到通知後立即到 Apple 官網確認。門店清單為 2025 年資料快照，請自行核對更新。", 12f, 0xFF687386.toInt()), marginTop(18))
        updateStatus()
    }

    private fun marginTop(dp: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = (dp * resources.displayMetrics.density).toInt()
    }

    private fun label(textValue: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 8, 0, 8)
    }

    private fun button(caption: String, action: () -> Unit) = Button(this).apply {
        text = caption
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun openSelectedProductPage() {
        val partNumber = skuField.text.toString().lineSequence()
            .map { it.substringBefore("|").trim().uppercase() }
            .firstOrNull { Regex("^[A-Z0-9]+CH/A$").matches(it) }
        val url = partNumber?.let {
            "https://www.apple.com.cn/shop/buy-iphone/iphone-duo/${it.lowercase()}"
        } ?: "https://www.apple.com.cn/shop/buy-iphone/iphone-duo"
        openInDefaultBrowser(url)
    }

    private fun openInDefaultBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "找不到可開啟 Apple 官網的瀏覽器", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "無法開啟 Apple 官網", Toast.LENGTH_LONG).show()
        }
    }
    private fun saveAndValidate(): Boolean {
        val skus = skuField.text.toString().lines().map { it.trim() }.filter { it.isNotBlank() }
        if (skus.isEmpty()) {
            Toast.makeText(this, "請先加入至少一個 Duo 型號代碼", Toast.LENGTH_LONG).show(); return false
        }
        val skuPattern = Regex("^[A-Z0-9]{5,}CH/A\\s*\\|\\s*.+$")
        if (skus.any { !skuPattern.matches(it) }) {
            Toast.makeText(this, "型號請使用「中國版代碼CH/A | 容量／顏色」格式", Toast.LENGTH_LONG).show(); return false
        }
        val stores = storeField.text.toString().split(Regex("[,\\s，]+"))
            .map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        if (stores.isEmpty() || stores.any { !Regex("^R\\d+$").matches(it) }) {
            Toast.makeText(this, "請輸入有效門店編號，例如 R484", Toast.LENGTH_LONG).show(); return false
        }
        prefs.edit().putString("skus", skus.joinToString("\n"))
            .putString("stores", stores.joinToString(",")).apply()
        return true
    }

    private fun updateStatus() {
        val enabled = StockCheckJobService.isEnabled(this)
        val last = prefs.getString("last_checked", "尚未查詢") ?: "尚未查詢"
        val errors = prefs.getInt("last_error_count", 0)
        val found = prefs.getString("available_summary", "目前未記錄到有貨") ?: "目前未記錄到有貨"
        status.text = "狀態：${if (enabled) "監測中（約每分鐘）" else "已停止"}\n最近查詢：$last\n最近一輪錯誤：$errors\n$found"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 31)
        }
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized) updateStatus() }
}
