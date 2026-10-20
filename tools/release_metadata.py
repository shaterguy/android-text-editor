#!/usr/bin/env python3
"""Create/check an unsigned APK handoff. No signing credentials are used here."""
import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path

BUILD_FILES = ('app/build.gradle', 'build.gradle', 'settings.gradle', 'gradle.properties',
               'gradle/wrapper/gradle-wrapper.properties', 'gradle/wrapper/gradle-wrapper.jar')


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


def source_identity(source):
    if not re.fullmatch(r'[0-9a-f]{40}', source):
        raise ValueError('Source must be a full lowercase commit SHA')
    git('cat-file', '-e', source + '^{commit}')
    gradle = git('show', source + ':app/build.gradle')
    name = re.search(r'versionName\s+"([^"]+)"', gradle).group(1)
    code = re.search(r'versionCode\s+(\d+)', gradle).group(1)
    digests = {}
    for path in BUILD_FILES:
        data = subprocess.check_output(['git', 'show', source + ':' + path])
        digests[path] = hashlib.sha256(data).hexdigest()
    return {'source_sha': source, 'variant': 'release', 'application_id': 'com.shaterguy.opentext',
            'version_name': name, 'version_code': code, 'build_inputs': digests}


def apk_identity(apk, aapt):
    result = subprocess.check_output([aapt, 'dump', 'badging', str(apk)], text=True)
    match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", result, re.M)
    if not match:
        raise ValueError('APK package metadata is unreadable')
    if 'application-debuggable' in result:
        raise ValueError('Debuggable APK is forbidden')
    return dict(zip(('application_id', 'version_code', 'version_name'), match.groups()))


def validate(metadata, expected, actual, apk_digest):
    if metadata.get('schema') != 1 or metadata.get('unsigned_sha256') != apk_digest:
        raise ValueError('Unsigned digest or metadata schema mismatch')
    for key, value in expected.items():
        if metadata.get(key) != value:
            raise ValueError('Source/build metadata mismatch: ' + key)
    for key, value in actual.items():
        if expected[key] != value:
            raise ValueError('APK identity mismatch: ' + key)
    if not isinstance(metadata.get('build_environment'), str) or not metadata['build_environment'].strip():
        raise ValueError('Build environment is required')


def certificate_digest(output, expected):
    expected = expected.replace(':', '').lower()
    found = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', output, re.M)
    if not re.fullmatch(r'[0-9a-f]{64}', expected) or len(found) != 1 or found[0].lower() != expected:
        raise ValueError('Unexpected signing certificate')


def assert_unsigned(apk):
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise ValueError('APK ZIP integrity check failed')
        if any(re.fullmatch(r'META-INF/[^/]+\.(RSA|DSA|EC|SF)', name, re.I) for name in archive.namelist()):
            raise ValueError('Input APK already contains a JAR signature')
    if b'APK Sig Block 42' in Path(apk).read_bytes():
        raise ValueError('Input APK already contains an APK signing block')

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode', choices=('create', 'verify', 'certificate'))
    p.add_argument('--apk'); p.add_argument('--metadata'); p.add_argument('--source')
    p.add_argument('--aapt'); p.add_argument('--environment')
    p.add_argument('--certificate-output'); p.add_argument('--expected-certificate')
    args = p.parse_args()
    if args.mode == 'certificate':
        certificate_digest(Path(args.certificate_output).read_text(), args.expected_certificate)
        return
    assert_unsigned(args.apk)
    expected = source_identity(args.source)
    actual = apk_identity(args.apk, args.aapt)
    digest = sha256(args.apk)
    if args.mode == 'create':
        if git('rev-parse', 'HEAD') != args.source or git('status', '--porcelain', '--untracked-files=normal'):
            raise ValueError('Build handoff requires a clean exact-source checkout')
        metadata = dict(expected, schema=1, unsigned_sha256=digest, build_environment=args.environment)
        validate(metadata, expected, actual, digest)
        Path(args.metadata).write_text(json.dumps(metadata, indent=2) + '\n')
    else:
        validate(json.loads(Path(args.metadata).read_text()), expected, actual, digest)
    print('Validated unsigned APK:', digest)


if __name__ == '__main__':
    main()
