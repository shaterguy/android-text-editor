import copy
import unittest
import tempfile
import zipfile
from pathlib import Path
from release_metadata import validate, certificate_digest, assert_unsigned


class ReleaseMetadataTests(unittest.TestCase):
    def setUp(self):
        self.expected = {'source_sha': 'a' * 40, 'variant': 'release',
                         'application_id': 'com.shaterguy.opentext', 'version_name': 'fixture',
                         'version_code': '2', 'build_inputs': {'app/build.gradle': 'b' * 64}}
        self.actual = {k: self.expected[k] for k in ('application_id', 'version_name', 'version_code')}
        self.metadata = dict(self.expected, schema=1, unsigned_sha256='c' * 64, build_environment='test')

    def test_valid(self):
        validate(self.metadata, self.expected, self.actual, 'c' * 64)

    def test_metadata_mismatches(self):
        for key in self.metadata:
            with self.subTest(key=key), self.assertRaises(ValueError):
                changed = copy.deepcopy(self.metadata)
                changed[key] = None
                validate(changed, self.expected, self.actual, 'c' * 64)

    def test_apk_mismatches(self):
        for key in self.actual:
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate(self.metadata, self.expected, dict(self.actual, **{key: 'wrong'}), 'c' * 64)

    def test_unsigned_input(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'input.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('AndroidManifest.xml', 'fixture')
            assert_unsigned(apk)
            with zipfile.ZipFile(apk, 'a') as archive:
                archive.writestr('META-INF/CERT.RSA', 'fixture')
            with self.assertRaises(ValueError):
                assert_unsigned(apk)
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('AndroidManifest.xml', 'APK Sig Block 42')
            with self.assertRaises(ValueError):
                assert_unsigned(apk)

    def test_signer(self):
        line = 'Signer #1 certificate SHA-256 digest: ' + 'a' * 64 + '\n'
        certificate_digest(line, 'a' * 64)
        for output, expected in [('', 'a' * 64), (line, 'b' * 64), (line + line, 'a' * 64), (line, '')]:
            with self.subTest(output=output), self.assertRaises(ValueError):
                certificate_digest(output, expected)


if __name__ == '__main__':
    unittest.main()
