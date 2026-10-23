"""Checks for tools/engine/terrain_reference.py. Run: python3 -I tools/engine/test_terrain_reference.py"""
import importlib.util
import math
import os
import unittest


def _load(name, rel):
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), rel)
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


ter = _load("terrain_reference", "terrain_reference.py")

A_E_M = 4.0 / 3.0 * 6371000.0


def flat_profile(d_m, h=0.0):
    dist = ter.profile_distances_m(d_m)
    return dist, [h] * len(dist)


class JFunction(unittest.TestCase):
    def test_values(self):
        self.assertEqual(ter.j_nu(-0.78), 0.0)
        self.assertEqual(ter.j_nu(-2.0), 0.0)
        self.assertAlmostEqual(ter.j_nu(0.0), 6.9 + 20 * math.log10(math.sqrt(1.01) - 0.1), places=12)
        self.assertGreater(ter.j_nu(-0.77), 0.0)


class Sampling(unittest.TestCase):
    def test_every_90_m_plus_shorter_last_step(self):
        self.assertEqual(ter.profile_distances_m(200.0), [0.0, 90.0, 180.0, 200.0])
        self.assertEqual(ter.profile_distances_m(180.0), [0.0, 90.0, 180.0])
        self.assertEqual(ter.profile_distances_m(50.0), [0.0, 50.0])

    def test_great_circle_points_follow_the_radial(self):
        lat, lon = -35.3, 149.1
        end = ter.destination(lat, lon, 37.0, 25000.0)
        for d in (0.0, 90.0, 12345.0, 25000.0):
            p = ter.great_circle_point(lat, lon, end[0], end[1], d / 25000.0)
            q = ter.destination(lat, lon, 37.0, d)
            self.assertAlmostEqual(p[0], q[0], places=9)
            self.assertAlmostEqual(p[1], q[1], places=9)

    def test_bilinear_and_missing(self):
        # 3 x 3 nodes, row 0 south; h = 10*row + col.
        dem = ter.DemGrid(south_index=-42000, west_index=178800, rows=3, cols=3,
                          h=[10 * r + c for r in range(3) for c in range(3)])
        lat0, lon0 = -42000 / 1200.0, 178800 / 1200.0
        s = 1 / 1200.0
        self.assertAlmostEqual(dem.height(lat0 + 0.5 * s, lon0 + 0.25 * s), 5.25, places=6)
        self.assertAlmostEqual(dem.height(lat0 + 2 * s, lon0 + 2 * s), 22.0, places=6)  # north-east node
        self.assertIsNone(dem.height(lat0 - 0.01 * s, lon0))
        self.assertIsNone(dem.height(lat0, lon0 + 2.01 * s))


class KnifeEdge(unittest.TestCase):
    """One 60 m ridge sample on flat ground, 10 km path, tx 30 m, rx 1.5 m, 1800 MHz."""

    def setUp(self):
        self.dist, self.h = flat_profile(10000.0)
        self.k = 56                                  # d1 = 5040 m, d2 = 4960 m
        self.h[self.k] = 60.0

    def test_luc_is_j_of_hand_nu(self):
        lam = 3e8 / 1800e6
        d1, d2, d = 5040.0, 4960.0, 10000.0
        line = 30.0 + (1.5 - 30.0) * d1 / d
        bulge = d1 * d2 / (2.0 * A_E_M)
        h_clear = 60.0 + bulge - line                # 45.835 m above the tx-rx line
        nu = h_clear * math.sqrt(2.0 * d / (lam * d1 * d2))
        self.assertAlmostEqual(nu, 3.17568, places=4)
        self.assertAlmostEqual(ter.j_nu(nu), 22.9004, places=3)   # by hand: 6.9 + 20 log10(6.30985)
        l_uc, l_b = ter.bullington([x / 1000.0 for x in self.dist], self.h, 30.0, 1.5, 1800.0)
        self.assertAlmostEqual(l_uc, ter.j_nu(nu), places=9)
        self.assertAlmostEqual(l_b, l_uc + (1 - math.exp(-l_uc / 6.0)) * (10 + 0.02 * 10.0), places=9)

    def test_excess_is_positive_and_below_actual(self):
        bulla, bulls, terr = ter.terrain_excess(self.dist, self.h, 30.0, 1.5, 1800.0)
        self.assertGreater(terr, 15.0)
        self.assertAlmostEqual(terr, bulla - bulls, places=12)


class Sanity(unittest.TestCase):
    def test_flat_earth_is_zero(self):
        for elev in (0.0, 612.0):
            for d in (500.0, 10000.0, 60000.0):
                dist, h = flat_profile(d, elev)
                bulla, bulls, terr = ter.terrain_excess(dist, h, elev + 30.0, elev + 1.5, 1800.0)
                self.assertAlmostEqual(bulla, bulls, places=9)
                self.assertAlmostEqual(terr, 0.0, places=9)   # exact up to rounding of the shifted heights
                self.assertEqual(ter.quantise(terr), 0)

    def test_los_over_low_hill_is_zero(self):
        dist, h = flat_profile(10000.0)
        for i, x in enumerate(dist):           # 5 m hill, 2 km wide, mid path; tx/rx 60 m
            h[i] = max(0.0, 5.0 * (1 - abs(x - 5000.0) / 1000.0))
        d_km = [x / 1000.0 for x in dist]
        l_uc, _ = ter.bullington(d_km, h, 60.0, 60.0, 1800.0)
        self.assertLess(ter.nu_max_los(d_km, h, 60.0, 60.0, 1800.0), -0.78)
        self.assertEqual(l_uc, 0.0)
        self.assertEqual(ter.terrain_excess(dist, h, 60.0, 60.0, 1800.0)[2], 0.0)

    def test_short_path_without_interior_samples_is_zero(self):
        self.assertEqual(ter.terrain_excess([0.0, 60.0], [100.0, 300.0], 130.0, 301.5, 700.0), (0.0, 0.0, 0.0))

    def test_raising_a_ridge_never_reduces_the_excess(self):
        for f in (700.0, 1800.0, 3500.0):
            for d, k in ((10000.0, 56), (30000.0, 100), (30000.0, 300)):
                dist, h = flat_profile(d, 200.0)
                last = -1.0
                for top in range(200, 420, 5):
                    h[k] = float(top)
                    terr = ter.terrain_excess(dist, h, 230.0, 201.5, f)[2]
                    self.assertGreaterEqual(terr, last - 1e-9, (f, d, k, top))
                    last = terr

    def test_smooth_surface_of_a_plane_is_the_plane(self):
        dist = ter.profile_distances_m(5000.0)
        h = [100.0 + 0.01 * x for x in dist]
        h_st, h_sr = ter.smooth_terminal_heights([x / 1000.0 for x in dist], h, 130.0, 151.5)
        self.assertAlmostEqual(h_st, 100.0, places=9)
        self.assertAlmostEqual(h_sr, 150.0, places=9)


class Table(unittest.TestCase):
    def test_bins(self):
        r = ter.range_bins_m()
        self.assertEqual(len(r), 256)
        self.assertAlmostEqual(r[0], 50.0, places=9)
        self.assertAlmostEqual(r[255], 80000.0, places=6)

    def test_quantise(self):
        self.assertEqual(ter.quantise(0.0), 0)
        self.assertEqual(ter.quantise(0.125), 1)
        self.assertEqual(ter.quantise(10.0), 40)
        self.assertEqual(ter.quantise(63.75), 255)
        self.assertEqual(ter.quantise(200.0), 255)

    def _ridge_dem(self):
        # 0.1 x 0.1 degree, a north-south ridge 3 km east of the site.
        rows = cols = 121
        south, west = int(-35.35 * 1200), int(149.0 * 1200)
        h = []
        for r in range(rows):
            for c in range(cols):
                lon = (west + c) / 1200.0
                h.append(500 + (200 if abs(lon - 149.0867) < 0.002 else 0))
        return ter.DemGrid(south, west, rows, cols, h)

    def test_cells_match_point_profiles_and_mask(self):
        dem = self._ridge_dem()
        lat, lon = -35.3, 149.05
        codes, mask = ter.polar_table(dem, lat, lon, 30.0, 1800.0, radials=[90, 270])
        r = ter.range_bins_m()
        for b in (90, 270):
            for j in (0, 100, 150, 180, 200):
                end = ter.destination(lat, lon, float(b), r[j])
                terr, complete = ter.point_excess(dem, lat, lon, 30.0, end[0], end[1], 1800.0)
                k = b * 256 + j
                self.assertEqual(ter.mask_bit(mask, k), 1 if complete else 0)
                self.assertEqual(codes[k], ter.quantise(terr) if complete else 0)
        # East: behind the ridge at 3 km there is loss; the grid ends ~3.3 km west -> incomplete.
        self.assertGreater(max(codes[90 * 256 + j] for j in range(256) if 4000 < r[j] < 5000), 20)
        self.assertEqual(ter.mask_bit(mask, 270 * 256 + 255), 0)
        self.assertEqual(ter.mask_bit(mask, 270 * 256 + 0), 1)

    def test_lookup_interpolates_and_needs_complete_cells(self):
        codes = [0] * (360 * 256)
        mask = bytearray([0xFF] * (360 * 256 // 8))
        codes[10 * 256 + 100] = 40
        codes[11 * 256 + 100] = 80
        r = ter.range_bins_m()
        self.assertAlmostEqual(ter.table_lookup(codes, mask, 10.25, r[100])[0], 10.0 * 0.75 + 20.0 * 0.25, places=6)
        self.assertEqual(ter.table_lookup(codes, mask, 10.0, 49.0), (0.0, True))
        self.assertEqual(ter.table_lookup(codes, mask, 10.0, 80001.0), (0.0, False))
        mask[(11 * 256 + 100) >> 3] &= ~(1 << ((11 * 256 + 100) & 7))
        self.assertEqual(ter.table_lookup(codes, mask, 10.25, r[100]), (0.0, False))
        codes[359 * 256 + 5] = 4
        self.assertAlmostEqual(ter.table_lookup(codes, mask, 359.5, r[5])[0], 0.5, places=9)  # wraps to 0


if __name__ == "__main__":
    unittest.main()
