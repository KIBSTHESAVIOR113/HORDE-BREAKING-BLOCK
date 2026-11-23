#!/usr/bin/env python3
"""Pack the project into a ZIP, excluding build outputs. Usage: python3 make_zip.py <project_root> <out.zip>"""
import sys, zipfile, pathlib
root = pathlib.Path(sys.argv[1]).resolve(); out = pathlib.Path(sys.argv[2])
skip = {"build", ".gradle", ".idea", "out", ".git"}
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for p in sorted(root.rglob("*")):
        if p.is_file() and not (set(p.relative_to(root).parts) & skip):
            z.write(p, (pathlib.Path(root.name) / p.relative_to(root)).as_posix())
print("wrote", out, out.stat().st_size, "bytes")
