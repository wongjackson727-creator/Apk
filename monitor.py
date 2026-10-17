#!/usr/bin/env python3
"""Hourly Apple China retail pickup monitor for configured iPhone Duo SKUs."""
from __future__ import annotations
import argparse
import json
import logging
import os
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
CONFIG_PATH = Path(os.getenv("CONFIG_PATH", ROOT / "config.json"))
STATE_PATH = Path(os.getenv("STATE_PATH", ROOT / "state.json"))
API_URL = "https://www.apple.com.cn/shop/retail/pickup-message"
UA = "Mozilla/5.0 (compatible; DuoPickupMonitor/1.0; +https://www.apple.com.cn/)"


def load_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return default


def save_json(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def request_json(url: str, params: dict, timeout: int = 20):
    req = Request(url + "?" + urlencode(params), headers={
        "User-Agent": UA,
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer": "https://www.apple.com.cn/shop/buy-iphone/",
    })
    with urlopen(req, timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f"Apple returned HTTP {response.status}")
        return json.loads(response.read().decode("utf-8"))


def pickup_query(part_number: str, store_number: str, request_fn=request_json):
    # Apple China pickup-message endpoint. Keep one store per request and group
    # SKU checks by the outer polling loop; do not fan out concurrent requests.
    data = request_fn(API_URL, {
        "pl": "true", "mts.0": "regular", "mts.1": "compact",
        "cppart": "UNLOCKED/CH", "parts.0": part_number, "store": store_number,
    })
    for store in data.get("body", {}).get("stores", []):
        if store.get("storeNumber") != store_number:
            continue
        info = store.get("partsAvailability", {}).get(part_number, {})
        display = info.get("pickupDisplay", "unavailable")
        return display == "available", display
    return False, "unavailable"


def notify(config: dict, message: str):
    n = config.get("notifications", {})
    sent = False
    if n.get("telegram_bot_token") and n.get("telegram_chat_id"):
        url = f"https://api.telegram.org/bot{n['telegram_bot_token']}/sendMessage"
        body = urlencode({"chat_id": n["telegram_chat_id"], "text": message}).encode()
        req = Request(url, data=body, headers={"Content-Type": "application/x-www-form-urlencoded"})
        with urlopen(req, timeout=15) as r:
            if r.status != 200: raise RuntimeError(f"Telegram returned HTTP {r.status}")
        sent = True
    if n.get("bark_url"):
        req = Request(n["bark_url"].rstrip("/") + "/" + __import__("urllib.parse").parse.quote("iPhone Duo 有貨") + "/" + __import__("urllib.parse").parse.quote(message), headers={"User-Agent": UA})
        with urlopen(req, timeout=15) as r:
            if r.status >= 300: raise RuntimeError(f"Bark returned HTTP {r.status}")
        sent = True
    if n.get("webhook_url"):
        payload = json.dumps({"text": message, "content": message}, ensure_ascii=False).encode()
        req = Request(n["webhook_url"], data=payload, headers={"Content-Type": "application/json"})
        with urlopen(req, timeout=15) as r:
            if r.status >= 300: raise RuntimeError(f"Webhook returned HTTP {r.status}")
        sent = True
    return sent


def run_cycle(config: dict, state: dict, request_fn=request_json, notify_fn=notify):
    products = config.get("products", [])
    stores = config.get("stores", [])
    if not products:
        raise ValueError("config.json 的 products 目前是空的，請加入 Apple 中國官網的 iPhone Duo 型號代碼。")
    if not stores:
        raise ValueError("config.json 的 stores 目前是空的，請加入要監測的 Apple Store 門店編號。")
    available_now = set()
    successful = set()
    errors = []
    # Conservative sequential polling; request count = SKUs × stores per cycle.
    for store in stores:
        for product in products:
            key = f"{product['part_number']}@{store['store_number']}"
            try:
                available, display = pickup_query(product["part_number"], store["store_number"], request_fn)
                successful.add(key)
                if available:
                    available_now.add(key)
                    if key not in state.get("available", []):
                        message = (f"iPhone Duo 門店取貨有貨\n型號：{product.get('name', product['part_number'])}\n"
                                   f"門店：{store.get('city','')} {store.get('name',store['store_number'])} ({store['store_number']})\n"
                                   f"查詢時間：{datetime.now().astimezone().isoformat(timespec='seconds')}\n"
                                   "請立即到 Apple 官網確認並下單；庫存可能隨時變動。")
                        notify_fn(config, message)
                        print("[有貨] " + key)
                logging.info("%s %s: %s", key, display, "有貨" if available else "無貨")
            except (HTTPError, URLError, TimeoutError, ValueError, RuntimeError, json.JSONDecodeError) as e:
                errors.append({"key": key, "error": str(e)})
                logging.warning("查詢失敗 %s: %s", key, e)
            # Avoid burst traffic across a large store/SKU list.
            time.sleep(float(config.get("request_delay_seconds", 1.5)))
    # Keep the previous status for keys whose requests failed; transient errors
    # must not look like a stockout and trigger duplicate alerts next cycle.
    previous = set(state.get("available", []))
    state["available"] = sorted(available_now | (previous - successful))
    state["last_checked"] = datetime.now(timezone.utc).isoformat()
    state["last_errors"] = errors
    return state


def validate(config):
    interval = int(config.get("interval_seconds", 3600))
    if interval < 3600:
        raise ValueError("為減少對 Apple 查詢服務的負載，監測間隔不得小於 3600 秒（1 小時）。")
    for p in config.get("products", []):
        if not re.fullmatch(r"[A-Z0-9]{5,}CH/A", p.get("part_number", "")):
            raise ValueError(f"型號代碼格式不正確（中國大陸通常以 CH/A 結尾）：{p}")
    for s in config.get("stores", []):
        if not re.fullmatch(r"R\d+", s.get("store_number", "")):
            raise ValueError(f"門店編號格式不正確：{s}")
    return interval


def main():
    parser = argparse.ArgumentParser(description="Apple 中國大陸 iPhone Duo 到店取貨庫存監測器")
    parser.add_argument("--once", action="store_true", help="只查詢一輪後結束")
    parser.add_argument("--config", type=Path, default=CONFIG_PATH, help="設定檔路徑")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    config = load_json(args.config, {})
    if not config.get("stores") and config.get("stores_file"):
        candidate = args.config.parent / config["stores_file"]
        if not candidate.exists():
            candidate = ROOT / config["stores_file"]
        store_data = load_json(candidate, {})
        config["stores"] = store_data.get("stores", [])
        if store_data.get("source_scan_date"):
            logging.warning("門店清單快照日期：%s；請核對 Apple 官方零售店頁面。", store_data["source_scan_date"])
    interval = validate(config)
    state = load_json(STATE_PATH, {"available": []})
    logging.info("監測啟動：%d 個 SKU × %d 家門店，間隔 %d 秒", len(config.get("products", [])), len(config.get("stores", [])), interval)
    while True:
        state = run_cycle(config, state)
        save_json(STATE_PATH, state)
        logging.info("本輪完成：%d 個門店-SKU 查詢，錯誤 %d；下輪約 %d 分鐘後", len(config["stores"])*len(config["products"]), len(state["last_errors"]), interval//60)
        if args.once: break
        time.sleep(interval)

if __name__ == "__main__":
    try: main()
    except KeyboardInterrupt: print("\n已停止"); sys.exit(130)
    except Exception as exc: logging.error("無法啟動：%s", exc); sys.exit(1)
