"""Find an existing client's prompt token through sudo/su's ancestor shells."""
import os
import re
from pathlib import Path

pid = os.getppid()
for _ in range(32):
    try:
        proc = Path('/proc') / str(pid)
        entries = (proc / 'environ').read_bytes().split(b'\0')
        for entry in entries:
            if entry.startswith(b'PS1='):
                token = re.search(rb'\x1eopenpocket_shell_([a-f0-9]{32}):', entry)
                if token:
                    print(token[1].decode('ascii'))
                    raise SystemExit(0)
        parent = next(line.split()[1] for line in (proc / 'status').read_text().splitlines() if line.startswith('PPid:'))
        pid = int(parent)
        if pid <= 1:
            break
    except (OSError, StopIteration, ValueError):
        break
