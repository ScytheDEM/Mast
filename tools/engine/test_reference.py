"""Checks for tools/engine/reference.py. Run: python3 -I tools/engine/test_reference.py"""
import importlib.util
import math
import os
import unittest


def _load():
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "reference.py")
    spec = importlib.util.spec_from_file_location("engine_reference", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


ref = _load()


class EirpPerRe(unittest.TestCase):
    def test_lte_20mhz(self):
        self.assertAlmostEqual(ref.eirp_per_re(60.0, "LTE", 1800.0, 20_000_000), 60.0 - 30.79, places=2)

    def test_nr_100mhz_3500(self):
        self.assertAlmostEqual(ref.eirp_per_re(60.0, "NR", 3500.0, 100_000_000), 60.0 - 35.15, places=2)

    def test_tables_and_fallback(self):
        self.assertEqual(ref.n_rb("LTE", 900.0, 1_400_000), 6)
        self.assertEqual(ref.n_rb("NR", 700.0, 20_000_000), 106)
        self.assertEqual(ref.n_rb("NR", 26000.0, 400_000_000), 264)
        # 9.8 MHz LTE is not in the table: floor(9.8e6 * 0.9 / 180e3) = 49
        self.assertEqual(ref.n_rb("LTE", 2100.0, 9_800_000), 49)
        self.assertEqual(ref.n_rb("LTE", 2100.0, 0), 6)


class PathLoss(unittest.TestCase):
    def test_hata_sanity(self):
        pl, sigma, model = ref.path_loss(900.0, 1.0, 30.0, "URBAN")
        self.assertEqual(model, "HATA")
        self.assertAlmostEqual(pl, 126.40, places=2)
        self.assertEqual(sigma, 8.0)

    def test_model_selection(self):
        self.assertEqual(ref.path_loss(1800.0, 1.0, 30.0, "URBAN")[2], "COST231")
        self.assertEqual(ref.path_loss(3500.0, 1.0, 30.0, "URBAN")[2], "UMA")
        self.assertEqual(ref.path_loss(3500.0, 1.0, 30.0, "RURAL")[2], "RMA")
        self.assertEqual(ref.path_loss(26000.0, 1.0, 30.0, "RURAL")[2], "UMI")

    def test_cost231_dense_urban_adds_3db_and_large_city_ahm(self):
        du = ref.path_loss(1800.0, 1.0, 30.0, "DENSE_URBAN")[0]
        u = ref.path_loss(1800.0, 1.0, 30.0, "URBAN")[0]
        logf = math.log10(1800.0)
        a_small = (1.1 * logf - 0.7) * 1.5 - (1.56 * logf - 0.8)
        a_large = 3.2 * math.log10(11.75 * 1.5) ** 2 - 4.97
        self.assertAlmostEqual(du - u, 3.0 - a_large + a_small, places=9)

    def test_uma_blend_is_power_domain(self):
        pl, sigma, _ = ref.path_loss(3500.0, 3.0, 30.0, "URBAN")
        d3, dh = 3000.0, 28.5
        d2 = math.sqrt(d3 ** 2 - dh ** 2)
        dbp = 4 * 29.0 * 0.5 * 3.5e9 / 3e8
        los = 28.0 + 40 * math.log10(d3) + 20 * math.log10(3.5) - 9 * math.log10(dbp ** 2 + dh ** 2)
        nlos = max(los, 13.54 + 39.08 * math.log10(d3) + 20 * math.log10(3.5))
        p = 18 / d2 + math.exp(-d2 / 63) * (1 - 18 / d2)
        want = -10 * math.log10(p * 10 ** (-los / 10) + (1 - p) * 10 ** (-nlos / 10))
        self.assertAlmostEqual(pl, want, places=9)
        self.assertAlmostEqual(sigma, p * 4 + (1 - p) * 6, places=12)

    def test_monotonic_in_distance(self):
        for area in ref.AREAS:
            for f in (700.0, 2100.0, 3500.0, 26000.0):
                prev = -1e9
                for i in range(1, 400):
                    pl = ref.path_loss(f, 0.02 * i, 30.0, area)[0]
                    self.assertGreaterEqual(pl, prev - 1e-9, (area, f, i))
                    prev = pl


class Antenna(unittest.TestCase):
    def test_boresight_far_field_only_vertical(self):
        a = ref.antenna_attenuation(90.0, 1000.0, 90.0, 65.0, 25.0, 15.0, 0.0, 30.0, 900.0)
        theta = math.degrees(math.atan2(28.5, 1000.0))
        tb = 32400 / (10 ** 1.5 * 65)
        self.assertAlmostEqual(a, 12 * ((theta - 3.0) / tb) ** 2, places=9)

    def test_back_is_capped(self):
        a = ref.antenna_attenuation(270.0, 1000.0, 90.0, 65.0, 25.0, 15.0, 0.0, 30.0, 900.0)
        self.assertLessEqual(a, 30.0)
        self.assertGreaterEqual(a, 25.0)

    def test_omni_has_no_horizontal_term(self):
        a1 = ref.antenna_attenuation(10.0, 500.0, None, None, None, None, 0.0, 20.0, 900.0)
        a2 = ref.antenna_attenuation(200.0, 500.0, None, None, None, None, 0.0, 20.0, 900.0)
        self.assertEqual(a1, a2)

    def test_hrp_lookup(self):
        att = [0] * 360
        att[10] = 40  # 10 dB at 10 degrees clockwise of the peak
        a = ref.horizontal_attenuation(110.4, 0.0, None, None, att, 100)
        self.assertEqual(a, 10.0)


class Probability(unittest.TestCase):
    def test_half_at_threshold(self):
        self.assertAlmostEqual(ref.coverage_probability(-118.0, -118.0, 8.0), 0.5, places=12)

    def test_codes(self):
        self.assertEqual(ref.rsrp_code(-140.5), 0)
        self.assertEqual(ref.rsrp_code(-140.0), 1)
        self.assertEqual(ref.rsrp_code(-10.0), 254)
        self.assertEqual(ref.sinr_code(-30.0), 0)
        self.assertEqual(ref.sinr_code(50.0), 255)
        self.assertEqual(ref.prob_code(1.0), 255)


class MaxRange(unittest.TestCase):
    def test_monotonic_and_capped(self):
        prev = 0.0
        for e in range(-20, 60, 5):
            r = ref.max_range_km(float(e), 900.0, 30.0, "RURAL")
            self.assertGreaterEqual(r, prev)
            self.assertLessEqual(r, 80.0)
            prev = r
        self.assertEqual(ref.max_range_km(60.0, 700.0, 60.0, "RURAL"), 80.0)


class Geometry(unittest.TestCase):
    def test_tile_of_canberra(self):
        self.assertEqual(ref.tile_for(-35.2809, 149.1300, 13), (7489, 4954))

    def test_bearing(self):
        self.assertAlmostEqual(ref.bearing_deg(-35.0, 149.0, -34.0, 149.0), 0.0, places=6)
        self.assertAlmostEqual(ref.bearing_deg(-35.0, 149.0, -35.0, 148.0), 270.0, delta=0.5)

    def test_area_class(self):
        self.assertEqual(ref.area_class(40), "DENSE_URBAN")
        self.assertEqual(ref.area_class(12), "URBAN")
        self.assertEqual(ref.area_class(3), "SUBURBAN")
        self.assertEqual(ref.area_class(2), "RURAL")


if __name__ == "__main__":
    unittest.main()
