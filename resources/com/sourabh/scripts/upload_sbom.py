#!/usr/bin/env python3
"""
Standalone helper to upload a single file to MinIO. Mirrors the upload step in build.py
so it can be called from any Jenkinsfile (or locally) without the full orchestrator.

    MINIO_ACCESS_KEY=... MINIO_SECRET_KEY=... \
        python3 upload_sbom.py \
            --endpoint http://localhost:9001 \
            --bucket sboms \
            --object my-app/42/sbom.cdx.json \
            --file sbom/sbom.cdx.json
"""

from __future__ import annotations

import argparse
import os
import shlex
import subprocess
import sys
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--endpoint", required=True)
    ap.add_argument("--bucket",   required=True)
    ap.add_argument("--object",   dest="object_key", required=True)
    ap.add_argument("--file",     required=True)
    ap.add_argument("--mc-image", default="minio/mc:latest")
    args = ap.parse_args()

    if not Path(args.file).exists():
        print(f"[ERROR] file not found: {args.file}", file=sys.stderr)
        return 2

    access = os.environ.get("MINIO_ACCESS_KEY")
    secret = os.environ.get("MINIO_SECRET_KEY")
    if not (access and secret):
        print("[ERROR] MINIO_ACCESS_KEY and MINIO_SECRET_KEY must be set", file=sys.stderr)
        return 2

    cwd = str(Path.cwd())
    inner = (
        "set -eu;"
        f" mc alias set sbomstore {shlex.quote(args.endpoint)} \"$MC_ACCESS\" \"$MC_SECRET\" >/dev/null;"
        f" mc mb --ignore-existing sbomstore/{args.bucket};"
        f" mc cp {shlex.quote(args.file)} sbomstore/{args.bucket}/{shlex.quote(args.object_key)}"
    )

    cmd = [
        "docker", "run", "--rm",
        "--network", "host",
        "-e", f"MC_ACCESS={access}",
        "-e", f"MC_SECRET={secret}",
        "-v", f"{cwd}:/work",
        "-w", "/work",
        "--entrypoint", "sh",
        args.mc_image,
        "-c", inner,
    ]
    print("[INFO] $ " + " ".join(shlex.quote(c) for c in cmd))
    subprocess.run(cmd, check=True)
    print(f"[INFO] uploaded -> {args.endpoint}/{args.bucket}/{args.object_key}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
