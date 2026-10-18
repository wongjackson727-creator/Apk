# iPhone Duo 中國大陸 Apple Store 庫存監測器

## Android App

已附 Android Studio 原生專案，請開啟 `android/` 資料夾建置並安裝到手機。具備約每分鐘背景查詢、編輯 SKU 與門店、立即查詢，以及 Android 本機到貨通知。啟用後會顯示常駐通知；一輪掃描可能因門店與 SKU 數量而超過一分鐘。詳細步驟見 [`android/README.md`](android/README.md)。GitHub Actions workflow 位於 `.github/workflows/build-apk.yml`；push 至 `main`／`master` 或手動執行後，APK 會作為 `app-debug-apk` artifact 上傳。


Android App 約每分鐘查詢；Python／Docker 監測器依設定檔間隔（預設一小時）查詢 Apple 中國大陸門店的到店取貨狀態。只在「無貨 → 有貨」時發送通知；Android App 提供 Apple 帳號登入與商品頁按鈕；到貨通知可直接用系統預設瀏覽器開啟該 SKU 的 Apple 官方商品頁。門店選擇、送出訂單與付款由使用者本人完成，App 不會自動鎖貨；狀態會保存在本機，避免每次輪詢重複通知。支援 Telegram、Bark 與一般 JSON Webhook。

> 監測來源為 Apple 官網使用的取貨查詢介面。Apple 沒有將此介面作為穩定的公開開發者 API；格式或存取限制可能變更。程式採序列查詢；Apple 未承諾此介面支援高頻輪詢，請勿將請求頻率提高到超出自身使用需求。顯示有貨後仍須以 Apple 官方購買頁確認，庫存不會保留給監測程式。

## 快速開始（Python 3.10+，不需第三方套件）

```bash
cp config.example.json config.json
```

在 `config.json` 填入：

1. `products`：iPhone Duo 每一種容量／顏色對應的 Apple 中國大陸型號代碼（通常以 `CH/A` 結尾）。各 SKU 各加一筆。請從 Apple 中國官網選好具體容量與顏色後的購買頁確認代碼；勿照抄其他型號或其他地區的代碼。
2. `stores_file` 預設讀取附帶的中國大陸門店清單（48 家快照，來源資料掃描日期 2025-10-06）。它方便啟動全國監測，但可能缺少新店或含有已變動資料；請依 Apple 官方零售店頁面核對，並編輯 `stores-china-mainland.json`。也可在設定檔直接提供 `stores` 陣列覆蓋清單。
3. 通知設定：填 Telegram Bot Token + Chat ID、Bark URL 或 JSON Webhook URL。可同時配置多種方式。

執行一次測試：

```bash
python3 monitor.py --once
```

持續監測：

```bash
python3 monitor.py
```

程式會記錄最近一輪結果至 `state.json`；查詢失敗寫入日誌和 `last_errors`，不會把失敗誤判成無貨。重啟後保留狀態，避免重複通知。要重新觸發目前有貨 SKU 的通知，可停止程式後刪除 `state.json`。

## Docker 長時間執行

```bash
cp config.example.json data-config.json
mkdir -p data
cp data-config.json data/config.json
cp stores-china-mainland.json data/stores-china-mainland.json
# 編輯 data/config.json 填好 Duo 的型號代碼和通知；核對門店清單

docker compose up -d --build
docker compose logs -f
```

`data/` 內設定與狀態會持久保存。請不要把包含 Token 的 `config.json` 或 `data/` 推送至公開 GitHub repository；已加入 `.gitignore`。

## 設定格式

```json
{
  "interval_seconds": 3600,
  "request_delay_seconds": 1.5,
  "stores_file": "stores-china-mainland.json",
  "products": [
    {"part_number": "以Apple頁面核對的代碼CH/A", "name": "Duo 512GB 深色"},
    {"part_number": "另一個CH/A代碼", "name": "Duo 1TB 淺色"}
  ],
  "notifications": {
    "telegram_bot_token": "",
    "telegram_chat_id": "",
    "bark_url": "",
    "webhook_url": ""
  }
}
```

## GitHub

本專案可直接推送至新 repository：

```bash
git init
git add .
git commit -m "Add hourly iPhone Duo pickup stock monitor"
```

目前 GitHub 帳戶連接器沒有列出可用 repository，也不提供建立新 repository 的操作，因此此工作階段未能直接推送到 GitHub。完成推送後，可再加上 GitHub Actions 排程；但對全天候監測而言，個人電腦或 VPS 上運行 Docker 的可用性通常更直接。
