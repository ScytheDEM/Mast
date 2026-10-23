"""Checks for tools/acma/hrp_reference.py. Run: python3 -I tools/acma/test_hrp_reference.py"""
import importlib.util
import io
import os
import unittest
import zipfile


def _load():
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hrp_reference.py")
    spec = importlib.util.spec_from_file_location("hrp_reference", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


hrp = _load()
HEADER = "SDD_ID,DEVICE_REGISTRATION_IDENTIFIER,START_ANGLE,STOP_ANGLE,POWER\r\n"


def device(sdd, powers, angles=None):
    angles = list(range(len(powers))) if angles is None else angles
    return "".join("%d,1,%d,%d,%s\r\n" % (sdd, a, a + 1, p) for a, p in zip(angles, powers))


def zip_of(text):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("LICENCE_.csv", text)
    buf.seek(0)
    return buf


def shape(peak_at):
    """Powers with a unique maximum of 30.0 at `peak_at`, falling 0.1 dB per degree (floor 5.0)."""
    out = []
    for a in range(360):
        d = min((a - peak_at) % 360, (peak_at - a) % 360)
        out.append("%.1f" % max(30.0 - 0.1 * d, 5.0))
    return out


class HrpRules(unittest.TestCase):
    def run_text(self, text, known):
        return hrp.run(zip_of(HEADER + text), known)

    def test_normalise_and_quantise(self):
        report, devices, patterns = self.run_text(device(7, shape(265)), {7})
        self.assertEqual(report, {"devices": 1, "patterns": 1, "skippedIncomplete": 0,
                                  "skippedUnknown": 0, "rows": 360})
        pid, peak_bearing, peak_dbw = devices[7]
        self.assertEqual((pid, peak_bearing, peak_dbw), (1, 265, 30.0))
        self.assertEqual(patterns[0][:3], [0, 0, 1])   # 0, 0.1, 0.2 dB -> 0, round(0.4), round(0.8)
        self.assertEqual(patterns[0][359], 0)          # 0.1 dB * 4 = 0.4 -> 0

    def test_first_maximum_wins(self):
        p = ["1"] * 360
        p[40] = p[200] = "9"
        _, devices, _ = self.run_text(device(7, p), {7})
        self.assertEqual(devices[7][1], 40)

    def test_dedup_rotated_shapes(self):
        report, devices, _ = self.run_text(device(7, shape(10)) + device(8, shape(300)), {7, 8})
        self.assertEqual(report["patterns"], 1)
        self.assertEqual(devices[7][0], devices[8][0])
        self.assertEqual((devices[7][1], devices[8][1]), (10, 300))

    def test_skips(self):
        out_of_order = list(range(360))
        out_of_order[10], out_of_order[11] = 11, 10
        text = (device(1, shape(0)[:359])                     # incomplete
                + device(2, shape(0), out_of_order)            # out of order
                + device(3, shape(0))                          # unknown SDD
                + device(4, shape(5)))                         # kept
        report, devices, _ = self.run_text(text, {1, 2, 4})
        self.assertEqual(report, {"devices": 1, "patterns": 1, "skippedIncomplete": 2,
                                  "skippedUnknown": 1, "rows": 359 + 360 * 3})
        self.assertEqual(list(devices), [4])

    def test_clamp_at_255(self):
        p = ["100"] + ["0"] * 359      # 100 dB below the peak -> 400 -> 255
        _, _, patterns = self.run_text(device(7, p), {7})
        self.assertEqual(patterns[0][1], 255)


if __name__ == "__main__":
    unittest.main()
