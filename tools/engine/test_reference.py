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

    def test_uma_blend_is_db_domain(self):
        pl, sigma, _ = ref.path_loss(3500.0, 3.0, 30.0, "URBAN")
        d3, dh = 3000.0, 28.5
        d2 = math.sqrt(d3 ** 2 - dh ** 2)
        dbp = 4 * 29.0 * 0.5 * 3.5e9 / 3e8
        los = 28.0 + 40 * math.log10(d3) + 20 * math.log10(3.5) - 9 * math.log10(dbp ** 2 + dh ** 2)
        nlos = max(los, 13.54 + 39.08 * math.log10(d3) + 20 * math.log10(3.5))
        p = 18 / d2 + math.exp(-d2 / 63) * (1 - 18 / d2)
        want = p * los + (1 - p) * nlos   # Ruling DD: dB-domain blend
        self.assertAlmostEqual(pl, want, places=9)
        self.assertAlmostEqual(sigma, p * 4 + (1 - p) * 6, places=12)

    def test_rma_hand_computed(self):
        # RMa, fc 3.5 GHz, d3D 500 m, h_BS 30, h 5, W 20, h_UT 1.5:
        #   d2D = sqrt(500^2 - 28.5^2) = 499.1871; d_BP = 2*pi*30*1.5*3.5e9/3e8 = 3298.67 -> PL1
        #   5^1.72 = 15.9305; PL1 = 20log10(40*pi*500*3.5/3) + 0.47791*log10(500) - 0.70094
        #        + 0.002*log10(5)*500 = 98.5904
        #   PL'NLOS = 161.04 - 7.1log10(20) + 7.5log10(5) - (24.37 - 3.7*(5/30)^2)log10(30)
        #        + (43.42 - 3.1log10(30))(log10(500) - 3) + 20log10(3.5) - (3.2(log10(17.625))^2 - 4.97) = 120.3893
        #   P = exp(-(499.1871 - 10)/1000) = 0.613125
        #   PL = 0.613125*98.5904 + 0.386875*120.3893 = 107.0239; sigma = 0.613125*4 + 0.386875*8 = 5.5475
        pl, sigma, model = ref.path_loss(3500.0, 0.5, 30.0, "RURAL")
        self.assertEqual(model, "RMA")
        self.assertAlmostEqual(pl, 107.0239, places=3)
        self.assertAlmostEqual(sigma, 5.5475, places=3)

    def test_umi_hand_computed(self):
        # UMi-Street Canyon, fc 26 GHz, d3D 200 m, h_BS 10, h_UT 1.5:
        #   d2D = sqrt(200^2 - 8.5^2) = 199.8193; d'BP = 4*9*0.5*26e9/3e8 = 1560 -> PL1
        #   PL1 = 32.4 + 21log10(200) + 20log10(26) = 109.0211
        #   PL'NLOS = 35.3log10(200) + 22.4 + 21.3log10(26) = 133.7653
        #   P = 18/199.8193 + exp(-199.8193/36)(1 - 18/199.8193) = 0.093617
        #   PL = 0.093617*109.0211 + 0.906383*133.7653 = 131.4488; sigma = 0.374467 + 7.087917 = 7.4624
        pl, sigma, model = ref.path_loss(26000.0, 0.2, 10.0, "RURAL")
        self.assertEqual(model, "UMI")
        self.assertAlmostEqual(pl, 131.4488, places=3)
        self.assertAlmostEqual(sigma, 7.4624, places=3)

    def test_hata_suburban_and_rural_corrections(self):
        # 900 MHz, 1 km, h 30: urban 126.4033 (log d = 0).
        #   SUBURBAN: -2(log10(900/28))^2 - 5.4 = -2*(1.50710)^2 - 5.4 = -9.9426 -> 116.4607
        #   RURAL: -4.78(2.95424)^2 + 18.33*2.95424 - 35.94 = -23.5064 -> 102.8969
        self.assertAlmostEqual(ref.path_loss(900.0, 1.0, 30.0, "SUBURBAN")[0], 116.4607, places=3)
        self.assertAlmostEqual(ref.path_loss(900.0, 1.0, 30.0, "RURAL")[0], 102.8969, places=3)

    def test_indoor_sigma_in_quadrature(self):
        self.assertAlmostEqual(ref.indoor_sigma(8.0), math.sqrt(64.0 + 4.4 ** 2), places=12)

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

    def test_downtilt_is_magnitude_capped_at_15(self):
        self.assertEqual(ref.effective_tilt(-6.0, 30.0, 900.0), 6.0)
        self.assertEqual(ref.effective_tilt(4.0, 30.0, 900.0), 4.0)
        self.assertEqual(ref.effective_tilt(-20.0, 30.0, 900.0), 15.0)
        self.assertEqual(ref.effective_tilt(-6.0, 30.0, 26000.0), 6.0)   # +2 only for the default
        self.assertEqual(ref.effective_tilt(0.0, 30.0, 26000.0), 5.0)

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


    def test_culling_range_is_max_over_classes(self):
        # Low-mounted 3.5 GHz carrier (h 3 m, eirpRe 45): UMa (URBAN) reaches ~12.9 km, RMa (RURAL) only ~3.9 km.
        urban = ref.max_range_km(45.0, 3500.0, 3.0, "URBAN")
        rural = ref.max_range_km(45.0, 3500.0, 3.0, "RURAL")
        self.assertGreater(urban, rural)
        r = ref.culling_range_km(45.0, 3500.0, 3.0)
        self.assertGreaterEqual(r, urban)
        self.assertEqual(r, max(ref.max_range_km(45.0, 3500.0, 3.0, a) for a in ref.AREAS))


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

    def test_site_area_counts_sites_around_the_site_itself_included(self):
        # Rule 5 as amended 2026-10-24: 11 others within 3 km + the site = 12 -> URBAN; one 3.5 km away is not counted.
        lat, lon = -33.87, 151.21
        sites = [(lat, lon)] + [(lat + 0.01 * k / 11, lon) for k in range(1, 12)] + [(lat + 0.0315, lon)]
        self.assertEqual(ref.site_area(sites, lat, lon), ("URBAN", 12))

    def test_carrier_area_column_overrides_the_tile_class(self):
        carriers = {"carrierArea": [1, None, 7]}
        self.assertEqual(ref.carrier_area(carriers, 0, "RURAL"), "URBAN")
        self.assertEqual(ref.carrier_area(carriers, 1, "RURAL"), "RURAL")
        self.assertEqual(ref.carrier_area(carriers, 2, "RURAL"), "RURAL")  # out of range = absent, as in the engine
        self.assertEqual(ref.carrier_area({}, 0, "SUBURBAN"), "SUBURBAN")


class Calibration(unittest.TestCase):
    def test_terms(self):
        self.assertEqual(ref.calibration_db(None, None, None, 500.0), 0.0)
        self.assertAlmostEqual(ref.calibration_db(-3.0, 10.0, 2.0, 20000.0), 7.0)
        self.assertAlmostEqual(ref.calibration_db(-3.0, 10.0, 2.0, 2.0e6), 7.0)     # clamped at 10x
        self.assertAlmostEqual(ref.calibration_db(-3.0, 10.0, 2.0, 0.0), -13.0)     # clamped at 0.1x
        self.assertEqual(ref.calibration_db(2.0, 5.0, 0.0, 500.0), 2.0)             # no usable median
        self.assertEqual(ref.calibrated_sigma(8.0, None), 8.0)
        self.assertEqual(ref.calibrated_sigma(8.0, 0.5), 4.0)
        self.assertEqual(ref.calibrated_sigma(6.0, 0.5), 4.0)                       # 4 dB floor
        self.assertEqual(ref.calibrated_sigma(6.0, 1.5), 6.0)

    def test_point_equals_tile_sample(self):
        carriers = {"n": 2, "lat": [-35.0, -35.01], "lon": [149.0, 149.02], "hTx": [30.0, 20.0], "az": [None, 90.0],
                    "tilt": [0.0, 0.0], "eirpRe": [20.0, 18.0], "freqMhz": [758.0, 1830.0], "hBw": [None, 65.0],
                    "fb": [None, 25.0], "gain": [None, 16.0], "rMaxKm": [40.0, 30.0], "tech": [0, 0],
                    "sectorKey": [1, 2], "freqKey": [1, 2], "hrpId": [0, 0], "hrpPeak": [0, 0],
                    "calOffsetDb": [3.0, None], "calSlope": [-4.0, None], "calDmedKm": [1.5, None],
                    "calSigmaScale": [0.5, None]}
        pts = ref.predict_point(carriers, [], -35.005, 149.01, "SUBURBAN")
        s = ref._sample(carriers, [0, 1], [], -35.005, 149.01, "SUBURBAN", False, -118.0)
        self.assertAlmostEqual(max(pts), s[0])
        self.assertEqual(ref.residual_pixel(0, 128), 1)
        self.assertEqual(ref.residual_pixel(127, 128), 255)
        self.assertEqual(ref.residual_pixel(5, 256), 5)


if __name__ == "__main__":
    unittest.main()
