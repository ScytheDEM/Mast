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


def rows_text(sdd, rows):
    """rows: (start, stop, power) tuples written as given."""
    return "".join("%d,1,%s,%s,%s\r\n" % (sdd, a, b, p) for a, b, p in rows)


def degree_rows(powers, angles=None):
    angles = list(range(len(powers))) if angles is None else angles
    return [(str(a), str(a + 1), powers[a]) for a in angles]


def zip_of(text):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("LICENCE_.csv", text)
    buf.seek(0)
    return buf


def shape(peak_at):
    """360 powers with a unique maximum of 30.0 at `peak_at`, falling 0.1 dB per degree (floor 5.0)."""
    out = []
    for a in range(360):
        d = min((a - peak_at) % 360, (peak_at - a) % 360)
        out.append("%.1f" % max(30.0 - 0.1 * d, 5.0))
    return out


class Rasterise(unittest.TestCase):
    def test_integer_grid_any_order(self):
        p = shape(100)
        order = list(range(180, 360)) + list(range(0, 180))
        self.assertEqual(hrp.rasterise(degree_rows(p, order)), [float(x) for x in p])

    def test_merged_rows(self):
        bins = hrp.rasterise([("0", "90", "10"), ("90", "91", "12"), ("91", "360", "11")])
        self.assertEqual(bins[:3], [10.0] * 3)
        self.assertEqual((bins[89], bins[90], bins[91], bins[359]), (10.0, 12.0, 11.0, 11.0))

    def test_single_row_full_circle(self):
        self.assertEqual(hrp.rasterise([("0", "360", "7.5")]), [7.5] * 360)

    def test_half_degree_grid_and_wrap(self):
        rows = [("359.5", "0.5", "20")] + [("%.1f" % (k - 0.5), "%.1f" % (k + 0.5), "%.1f" % (k / 10)) for k in range(1, 360)]
        bins = hrp.rasterise(rows)
        self.assertEqual(bins[0], 20.0)
        self.assertEqual(bins[1], 0.1)
        self.assertEqual(bins[359], 35.9)

    def test_half_degree_end_rows_cover_no_integer(self):
        # [0, 0.5) holds degree 0; [359.5, 360) holds none, so its POWER never conflicts.
        rows = [("0", "0.5", "20"), ("359.5", "360", "20")] + \
            [("%.1f" % (k - 0.5), "%.1f" % (k + 0.5), "%.1f" % (k / 10)) for k in range(1, 360)]
        self.assertEqual(hrp.rasterise(rows)[0], 20.0)

    def test_wrap_past_360(self):
        bins = hrp.rasterise([("330", "30", "5"), ("30", "330", "6")])
        self.assertEqual((bins[329], bins[330], bins[0], bins[29], bins[30]), (6.0, 5.0, 5.0, 5.0, 6.0))

    def test_fill_up_to_three_gaps_circular_linear(self):
        p = [float(k % 50) for k in range(360)]
        rows = [(str(a), str(a + 1), "%g" % p[a]) for a in range(360) if a not in (0, 200, 201)]
        bins = hrp.rasterise(rows)
        self.assertAlmostEqual(bins[0], 9.0 + (1.0 - 9.0) * 1 / 2)        # between 359 (9) and 1 (1)
        self.assertAlmostEqual(bins[200], 49.0 + (2.0 - 49.0) * 1 / 3)    # between 199 (49) and 202 (2)
        self.assertAlmostEqual(bins[201], 49.0 + (2.0 - 49.0) * 2 / 3)

    def test_rejections(self):
        full = degree_rows(shape(0))
        self.assertIsNone(hrp.rasterise([r for r in full if r[0] not in ("10", "11", "12", "13")]))   # 4 empty
        self.assertIsNone(hrp.rasterise(full + [("10", "12", "99")]))                             # conflict
        self.assertIsNotNone(hrp.rasterise(full + [("10", "11", full[10][2])]))                   # equal overlap
        self.assertIsNone(hrp.rasterise(full[:-1] + [("359", "360", "2400")]))                    # sentinel
        self.assertIsNone(hrp.rasterise(full[:-1] + [("359", "360", "nan")]))                     # not finite
        self.assertIsNone(hrp.rasterise(full[:-1] + [("359", "x", "1")]))                       # malformed
        self.assertIsNone(hrp.rasterise([("0", "60", "30")]))                                     # 60 deg sector


class HrpRules(unittest.TestCase):
    def run_text(self, text, known):
        return hrp.run(zip_of(HEADER + text), known)

    def test_normalise_and_quantise(self):
        report, devices, patterns = self.run_text(rows_text(7, degree_rows(shape(265))), {7})
        self.assertEqual(report, {"devices": 1, "patterns": 1, "skippedIncomplete": 0,
                                  "skippedUnknown": 0, "rows": 360})
        self.assertEqual(devices[7], (1, 265, 30.0))
        self.assertEqual(patterns[0][:3], [0, 0, 1])   # 0, 0.1, 0.2 dB -> 0, round(0.4), round(0.8)
        self.assertEqual(patterns[0][359], 0)

    def test_first_maximum_wins(self):
        p = ["1"] * 360
        p[40] = p[200] = "9"
        _, devices, _ = self.run_text(rows_text(7, degree_rows(p)), {7})
        self.assertEqual(devices[7][1], 40)

    def test_dedup_rotated_shapes(self):
        text = rows_text(7, degree_rows(shape(10))) + rows_text(8, degree_rows(shape(300)))
        report, devices, _ = self.run_text(text, {7, 8})
        self.assertEqual(report["patterns"], 1)
        self.assertEqual(devices[7][0], devices[8][0])
        self.assertEqual((devices[7][1], devices[8][1]), (10, 300))

    def test_skips_and_fills(self):
        missing0 = degree_rows(shape(5))[1:]                      # 359 rows, degree 0 filled
        missing4 = degree_rows(shape(5))[4:]                      # 356 rows -> incomplete
        shuffled = degree_rows(shape(9), list(range(359, -1, -1)))
        text = (rows_text(1, missing4) + rows_text(2, missing0) + rows_text(3, degree_rows(shape(0)))
                + rows_text(4, shuffled) + rows_text(5, [("0", "360", "1"), ("10", "11", "2")]))
        report, devices, _ = self.run_text(text, {1, 2, 4, 5})
        # The filled degree 0 of device 2 equals the original shape, so devices 2 and 4 share one pattern.
        self.assertEqual(report, {"devices": 2, "patterns": 1, "skippedIncomplete": 2,
                                  "skippedUnknown": 1, "rows": 356 + 359 + 360 + 360 + 2})
        self.assertEqual(list(devices), [2, 4])

    def test_clamp_at_255(self):
        p = ["100"] + ["0"] * 359      # 100 dB below the peak -> 400 -> 255
        _, _, patterns = self.run_text(rows_text(7, degree_rows(p)), {7})
        self.assertEqual(patterns[0][1], 255)


if __name__ == "__main__":
    unittest.main()
