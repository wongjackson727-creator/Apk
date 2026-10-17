import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("monitor", Path(__file__).parents[1] / "monitor.py")
monitor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(monitor)

class MonitorTests(unittest.TestCase):
    def test_pickup_query_parses_available(self):
        def fake(url, params):
            sku, store = params["parts.0"], params["store"]
            return {"body":{"stores":[{"storeNumber":store,"partsAvailability":{sku:{"pickupDisplay":"available"}}}]}}
        self.assertEqual(monitor.pickup_query("ABC123CH/A", "R484", fake), (True, "available"))

    def test_no_repeat_alert_while_stock_remains(self):
        config = {"products":[{"part_number":"ABC123CH/A","name":"Duo"}],"stores":[{"store_number":"R484","name":"Store","city":"Shenzhen"}],"request_delay_seconds":0}
        state = {"available": []}
        alerts = []
        def fake_request(url, params):
            sku, store = params["parts.0"], params["store"]
            return {"body":{"stores":[{"storeNumber":store,"partsAvailability":{sku:{"pickupDisplay":"available"}}}]}}
        notify = lambda cfg, msg: alerts.append(msg)
        state = monitor.run_cycle(config, state, fake_request, notify)
        state = monitor.run_cycle(config, state, fake_request, notify)
        self.assertEqual(len(alerts), 1)
        self.assertEqual(len(state["available"]), 1)


    def test_transient_error_keeps_previous_available_state(self):
        from urllib.error import URLError
        config = {"products":[{"part_number":"ABC123CH/A","name":"Duo"}],"stores":[{"store_number":"R484","name":"Store","city":"Shenzhen"}],"request_delay_seconds":0}
        def fail(url, params): raise URLError("temporary failure")
        state = monitor.run_cycle(config, {"available":["ABC123CH/A@R484"]}, fail, lambda *_: None)
        self.assertEqual(state["available"], ["ABC123CH/A@R484"])
        self.assertEqual(len(state["last_errors"]), 1)

    def test_interval_minimum_is_one_hour(self):
        with self.assertRaises(ValueError): monitor.validate({"interval_seconds":1800})

if __name__ == "__main__": unittest.main()
