import asyncio
import unittest
from unittest.mock import patch

import monitor_predictions as monitor


class PredictionTests(unittest.TestCase):
    def test_parses_firmware_v5_payload(self):
        result = monitor.parse_prediction(b"sleep,0.9375,5653,38,rest")
        self.assertEqual(result.action, "sleep")
        self.assertEqual(result.margin, 0.9375)
        self.assertEqual(result.latency_us, 5653)
        self.assertEqual(result.window, 38)
        self.assertEqual(result.source_label, "rest")

    def test_rejects_old_four_field_payload(self):
        with self.assertRaisesRegex(ValueError, "expected 5 fields"):
            monitor.parse_prediction(b"rest,0.9375,5653,38")

    def test_rejects_unknown_public_action(self):
        with self.assertRaisesRegex(ValueError, "unknown public action"):
            monitor.parse_prediction(b"collar_shake,0.5,1000,1,collar_shake")


class BridgeTests(unittest.IsolatedAsyncioTestCase):
    async def test_reports_only_action_changes(self):
        calls = []

        def fake_post(action):
            calls.append(action)
            return {"action": action}

        bridge = monitor.DesktopPetBridge()
        with patch.object(monitor, "post_pet_action", fake_post):
            task = asyncio.create_task(bridge.run())
            bridge.submit("sleep")
            await asyncio.sleep(0.05)
            bridge.submit("sleep")
            await asyncio.sleep(0.05)
            bridge.submit("walk")
            await asyncio.sleep(0.05)
            bridge.stop()
            await task

        self.assertEqual(calls, ["sleep", "walk"])


if __name__ == "__main__":
    unittest.main()
