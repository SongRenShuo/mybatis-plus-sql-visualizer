# -*- coding: utf-8 -*-
"""组件定位协议回归：不依赖正在运行的 IDE。"""
import unittest
from unittest.mock import patch

import robot


class RobotProtocolTest(unittest.TestCase):
    def test_find_components_uses_plural_endpoint_and_status(self):
        response = {"status": "SUCCESS", "elementList": [{"id": "1", "width": 50, "height": 20}]}
        with patch.object(robot, "_request", return_value=response) as request:
            self.assertEqual([{"id": "1", "width": 50, "height": 20}],
                             robot.find_components("//div"))
        self.assertEqual("/xpath/components", request.call_args.args[0])
        self.assertEqual({"xpath": "//div"}, request.call_args.args[1])

    def test_server_error_and_ambiguous_match_never_click(self):
        with patch.object(robot, "_request", return_value={"status": "ERROR", "message": "bad xpath"}):
            with self.assertRaisesRegex(robot.RobotServerError, "bad xpath"):
                robot.find_components("//div")
        two = [{"id": "1", "width": 50, "height": 20},
               {"id": "2", "width": 50, "height": 20}]
        with patch.object(robot, "find_components", return_value=two):
            with self.assertRaisesRegex(robot.RobotServerError, "2 个可见组件"):
                robot.find_one("//div")

    def test_component_screen_center_sums_ancestor_offsets(self):
        comp = {"id": "tab", "className": "TabLabel", "x": 12, "y": 2,
                "width": 80, "height": 30}
        chain = [{"className": "com.intellij.openapi.wm.impl.IdeFrameImpl", "x": 260, "y": 20},
                 {"className": "IdeRootPane", "x": 8, "y": 0},
                 {"className": "Panel", "x": 852, "y": 40}]
        with patch.object(robot, "find_components", return_value=chain):
            self.assertEqual((1172, 77), robot.component_screen_center("//div", comp))
        with patch.object(robot, "find_components", return_value=chain[1:]):
            with self.assertRaisesRegex(robot.RobotServerError, "完整祖先链"):
                robot.component_screen_center("//div", comp)
        with self.assertRaises(robot.RobotServerError):
            robot.component_center({"id": "hidden", "x": 0, "y": 0, "width": 0, "height": 40})

    def test_popup_window_coordinates_are_already_on_screen(self):
        comp = {"id": "jump", "className": "ActionMenuItem", "x": 1, "y": 162,
                "width": 247, "height": 26}
        chain = [{"className": "com.intellij.openapi.wm.impl.IdeFrameImpl", "x": 277, "y": 25},
                 {"className": "javax.swing.Popup$HeavyWeightWindow", "x": 1263, "y": 213},
                 {"className": "JRootPane", "x": 0, "y": 0}]
        with patch.object(robot, "find_components", return_value=chain):
            self.assertEqual((1387, 388), robot.component_screen_center("//div", comp))

    def test_nested_dialog_and_popup_use_nearest_window_origin(self):
        comp = {"x": 8, "y": 4, "width": 100, "height": 34}
        chain = [
            {"className": "com.intellij.openapi.wm.impl.IdeFrameImpl", "x": 277, "y": 25},
            {"className": "DialogWrapperPeerImpl$MyDialog", "x": 367, "y": 166},
            {"className": "DialogRootPane", "x": 8, "y": 0},
        ]
        with patch.object(robot, "find_components", return_value=chain):
            self.assertEqual((433, 187), robot.component_screen_center("//div", comp))
        floating = [chain[0], {"className": "NonModalWindowWrapper$FloatDialog", "x": 367, "y": 166}, chain[2]]
        with patch.object(robot, "find_components", return_value=floating):
            self.assertEqual((433, 187), robot.component_screen_center("//div", comp))
        popup = chain + [{"className": "javax.swing.Popup$HeavyWeightWindow", "x": 680, "y": 240}]
        with patch.object(robot, "find_components", return_value=popup):
            self.assertEqual((738, 261), robot.component_screen_center("//div", comp))

    def test_screenshot_decodes_signed_byte_array(self):
        png = b"\x89PNG\r\n\x1a\n" + b"payload"
        with patch.object(robot, "_request", return_value={
            "status": "SUCCESS", "bytes": [b if b < 128 else b - 256 for b in png]
        }):
            self.assertEqual(png, robot.screenshot())
        with patch.object(robot, "_request", return_value={"status": "ERROR", "message": "denied"}):
            with self.assertRaisesRegex(robot.RobotServerError, "denied"):
                robot.screenshot()


if __name__ == "__main__":
    unittest.main()
