import gzip
import hashlib
from pathlib import Path
import struct
import tempfile
import unittest

import prepare_geoid


class GeoidPackagingTest(unittest.TestCase):
    def test_bundled_model_has_the_reviewed_hash_and_complete_global_geometry(self):
        data = prepare_geoid.OUTPUT.read_bytes()
        self.assertEqual("4194ede8b34d4407f494b0f2150d40aea2d8a0e1f841ac376e7aa3e2698b91f8",
                         hashlib.sha256(data).hexdigest())
        unpacked = gzip.decompress(data)
        self.assertEqual((b"EGM96CM1", 721, 1440), struct.unpack(">8sHH", unpacked[:12]))
        self.assertEqual(12 + 721 * 1440 * 2, len(unpacked))

    def test_unrecognized_source_is_rejected_without_overwriting_an_existing_model(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "unknown.gtx"
            target = Path(directory) / "model.gz"
            source.write_bytes(b"not the pinned public model")
            target.write_bytes(b"previous model")
            with self.assertRaises(ValueError):
                prepare_geoid.prepare(source, target)
            self.assertEqual(b"previous model", target.read_bytes())
