#!/usr/bin/env python3
"""Review tracked/staged public files without printing credential contents."""
import argparse
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--staged', action='store_true', help='inspect index contents')
args = parser.parse_args()

def git(*command):
    return subprocess.check_output(['git', '-C', str(ROOT)] + list(command))

names = [p.decode('utf-8') for p in git('ls-files', '-z').split(b'\0') if p]
if not names:
    sys.exit('No tracked files; stage the reviewed public files first.')
root_files = {'.gitignore', '.gitattributes', 'README.md', 'LICENSE', 'CHANGELOG.md',
              'CONTRIBUTING.md', 'SECURITY.md', 'THIRD_PARTY_NOTICES.md'}
roots = {'android-client', 'docs', 'scripts', 'server', '.github'}
forbidden = {'.build-user', '.test-secrets', '.emulator', '.gradle', 'build',
             'artifacts', 'node_modules', '__pycache__', 'ssh-keys', 'server-fixes'}
binary_allowlist = {'android-client/gradle/wrapper/gradle-wrapper.jar',
                    'android-client/app/src/main/assets/keyboard/pinyin.bin'}
patterns = [
    ('private key', re.compile(rb'-----BEGIN (?:OPENSSH |RSA |EC |DSA |ENCRYPTED )?PRIVATE KEY-----')),
    ('GitHub credential', re.compile(rb'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b')),
    ('JWT', re.compile(rb'\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}')),
    ('provider key', re.compile(rb'\bsk-(?:proj-|ant-)?[A-Za-z0-9_-]{32,}')),
    ('personal Windows path', re.compile(rb'[A-Za-z]:[\\/]+Users[\\/]+[A-Za-z0-9_\-]+[\\/]')),
]
problems = []
for name in names:
    path = pathlib.PurePosixPath(name)
    if (len(path.parts) == 1 and name not in root_files) or (len(path.parts) > 1 and path.parts[0] not in roots):
        problems.append((name, 'outside public roots'))
    if forbidden.intersection(path.parts) or path.name == 'local.properties' or path.name.startswith('.env') or path.suffix.lower() in {'.key', '.pem', '.p12', '.jks', '.keystore', '.apk', '.zip', '.log', '.db'} or ('signing' in path.name.lower() and path.suffix == '.json'):
        problems.append((name, 'private/build artifact path'))
    data = git('show', ':' + name) if args.staged else (ROOT / name).read_bytes()
    if b'\0' in data:
        if name not in binary_allowlist:
            problems.append((name, 'unreviewed binary'))
        continue
    for label, pattern in patterns:
        if pattern.search(data):
            problems.append((name, label))
    # Private addresses are allowed only as explicit test fixtures, never defaults.
    if '/src/test/' not in name and '/src/androidTest/' not in name:
        for match in re.finditer(rb'\b(?:10|100|172|192)\.\d{1,3}\.\d{1,3}\.\d{1,3}\b', data):
            a, b, c, d = map(int, match.group().split(b'.'))
            if max(a, b, c, d) <= 255 and (a == 10 or (a == 100 and 64 <= b <= 127) or (a == 172 and 16 <= b <= 31) or (a == 192 and b == 168)):
                problems.append((name, 'private server address'))
if problems:
    for name, reason in problems:
        print('FAIL: {} ({})'.format(name, reason))
    sys.exit(1)
print('PASS: {} public files; credential patterns and artifact paths checked. Manual review is still required.'.format(len(names)))
