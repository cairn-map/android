import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location('release_version', Path(__file__).with_name('release-version.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
SOURCE = 'a' * 40


def previous(code=5, name='0.5.0', source='b' * 40):
    return dict(schema=1, applicationId='app.cairn.trek', versionCode=code, versionName=name, sourceCommit=source)


class ReleaseVersionTest(unittest.TestCase):
    def test_first_release_uses_declared_version(self):
        self.assertEqual(dict(skip='false', version_name='0.5.0', version_code='5'),
                         release.next_version(5, '0.5.0', None, SOURCE))

    def test_each_merge_advances_both_versions_without_a_source_version_bump(self):
        self.assertEqual(dict(skip='false', version_name='0.5.1', version_code='6'),
                         release.next_version(5, '0.5.0', previous(), SOURCE))
        self.assertEqual(dict(skip='false', version_name='0.5.10', version_code='15'),
                         release.next_version(5, '0.5.0', previous(14, '0.5.9'), SOURCE))

    def test_an_explicit_new_version_is_preserved_but_code_never_goes_backwards(self):
        self.assertEqual(dict(skip='false', version_name='0.6.0', version_code='15'),
                         release.next_version(6, '0.6.0', previous(14, '0.5.9'), SOURCE))

    def test_rerunning_a_published_source_is_a_noop(self):
        self.assertEqual({'skip': 'true'}, release.next_version(5, '0.5.0', previous(source=SOURCE), SOURCE))

    def test_prevent_releasing_stale_source_or_out_of_order_runs(self):
        earlier = previous(source='b' * 40)
        ancestry_queries = []
        def includes_old(old, new):
            ancestry_queries.append((old, new))
            return True
        self.assertIsNone(release.require_forward_release_source(earlier, SOURCE, includes_old))
        self.assertEqual(ancestry_queries, [('b' * 40, SOURCE)])
        with self.assertRaisesRegex(ValueError, 'must contain'):
            release.require_forward_release_source(earlier, SOURCE, lambda old, new: False)
        # An already-published source must remain a no-op.
        self.assertIsNone(release.require_forward_release_source(earlier, 'b' * 40, lambda *args: False))
        self.assertIsNone(release.require_forward_release_source(None, SOURCE, lambda *args: False))
        with self.assertRaisesRegex(ValueError, 'Invalid previous'):
            release.require_forward_release_source(previous(source='wrong'), SOURCE, includes_old)

    def test_invalid_manifest_versions_and_exhausted_codes_fail(self):
        invalid = [previous(name='v1.0.0'), previous(code=-1), previous(code=True),
                   previous(code=release.MAX_VERSION_CODE), dict(previous(), applicationId='another.app')]
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.next_version(5, '0.5.0', value, SOURCE)
        with self.assertRaises(ValueError):
            release.next_version(5, '0.5.0', None, 'main')


if __name__ == '__main__':
    unittest.main()
