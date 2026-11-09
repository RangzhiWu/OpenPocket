"""Resize the existing PTY identified by its client's unguessable shell token."""
import fcntl
import json
import os
from pathlib import Path
import pwd
import re
import struct
import sys
import termios

token, cols, rows = sys.argv[1:]
assert re.fullmatch('[a-f0-9]{32}', token)
cols, rows = int(cols), int(rows)
assert 2 <= cols <= 500 and 2 <= rows <= 300
owner = pwd.getpwnam(os.environ.get('OPENPOCKET_SHELL_USER', 'webui-agent')).pw_uid
marker = ('\x1eopenpocket_shell_' + token + ':').encode()
candidates = []
for proc in Path('/proc').iterdir():
    if not proc.name.isdigit():
        continue
    try:
        if (proc / 'comm').read_text().strip() != 'bash':
            continue
        if not any(e.startswith(b'PS1=') and marker in e for e in (proc / 'environ').read_bytes().split(b'\0')):
            continue
        uid = proc.stat().st_uid
        if uid not in (owner, 0):
            continue
        candidates.append((uid != owner, int(proc.name), proc))
    except (OSError, ValueError):
        continue
for _, pid, proc in sorted(candidates):
    try:
        fd = os.open(proc / 'fd/0', os.O_RDONLY | os.O_NOCTTY)
        try:
            fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack('HHHH', rows, cols, 0, 0))
        finally:
            os.close(fd)
        print(json.dumps({'pid': pid, 'cols': cols, 'rows': rows}))
        break
    except OSError:
        continue
else:
    raise SystemExit('The original terminal PTY was not found')
