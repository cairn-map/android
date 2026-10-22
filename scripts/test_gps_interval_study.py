import unittest

from gps_interval_study import cumulative, fixtures, sample, segment_distance


class IntervalGeometryTests(unittest.TestCase):
    def test_straight_track_keeps_geometry_at_every_interval_and_phase(self):
        for interval in (1, 20, 30, 60, 300):
            for phase in (0, .25, .5, .75):
                row = sample(fixtures()["straight"], 4, interval, phase)
                self.assertAlmostEqual(100, row["length_retained_percent"])
                self.assertAlmostEqual(0, row["max_error_m"])

    def test_sampling_detects_the_loss_of_an_unsampled_hairpin(self):
        points = [(0, 0), (100, 0), (100, 10), (0, 10)]
        row = sample(points, 3.6, 210, 0)
        self.assertAlmostEqual(100, row["max_error_m"])
        self.assertAlmostEqual(1000 / 210, row["length_retained_percent"])

    def test_exact_bends_are_included_in_error_probes(self):
        row = sample([(0, 0), (1, 0), (1, 1)], 7.2, 60, 0)
        self.assertAlmostEqual(2 ** -.5, row["max_error_m"])

    def test_zero_length_segment_and_fixture_distances(self):
        self.assertEqual(5, segment_distance((3, 4), (0, 0), (0, 0)))
        self.assertEqual([0, 5], cumulative([(0, 0), (3, 4)]))


if __name__ == "__main__":
    unittest.main()
