#!/usr/bin/env python3
"""
build.py — orchestrator used by the `pythonBuild` Jenkins step.

Performs (configurable via JSON config):
  1. docker build -f <dockerfile> -t <ref> <context>
  2. (optional) docker push
  3. trivy image <ref> --format cyclonedx --output sbom/sbom.cdx.json
       (run via the aquasec/trivy Docker image)
  4. (optional) upload SBOM to MinIO using `mc` (run via minio/mc Docker image).
       Credentials come from the env vars MINIO_ACCESS_KEY / MINIO_SECRET_KEY which
       Jenkins injects via withCredentials.

Designed to be the single command Jenkins runs:
    python3 build.py --config config.json --output output.json
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
from pathlib import Path
from typing import Any


# ---------- helpers --------------------------------------------------------- #

def log(level: str, msg: str) -> None:
    print(f"[{level}] [pythonBuild] {msg}", flush=True)


def run(cmd: list[str], *, check: bool = True, env: dict | None = None,
        capture: bool = False) -> subprocess.CompletedProcess:
    log("INFO", "$ " + " ".join(shlex.quote(c) for c in cmd))
    return subprocess.run(
        cmd,
        check=check,
        env=env or os.environ.copy(),
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )


def require(cond: bool, msg: str) -> None:
    if not cond:
        log("ERROR", msg)
        sys.exit(2)


# ---------- steps ----------------------------------------------------------- #

def docker_build(cfg: dict[str, Any]) -> str:
    image = cfg["imageName"]
    tag = cfg.get("tag", "latest")
    registry = cfg.get("registry", "")
    dockerfile = cfg.get("dockerfile", "Dockerfile")
    context = cfg.get("context", ".")
    build_args: dict[str, str] = cfg.get("buildArgs", {}) or {}

    require(Path(dockerfile).exists(),
            f"Dockerfile not found at {dockerfile}")

    ref = f"{registry}/{image}:{tag}" if registry else f"{image}:{tag}"

    cmd = ["docker", "build", "-f", dockerfile, "-t", ref]
    for k, v in build_args.items():
        cmd += ["--build-arg", f"{k}={v}"]
    cmd.append(context)
    run(cmd)

    if cfg.get("push") and registry:
        run(["docker", "push", ref])

    return ref


def trivy_sbom(image_ref: str, sbom_cfg: dict[str, Any]) -> str:
    fmt = sbom_cfg.get("format", "cyclonedx")
    out_dir = sbom_cfg.get("outputDir", "sbom")
    out_file = sbom_cfg.get("outputFile", "sbom.cdx.json")
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    rel_out = f"{out_dir}/{out_file}"

    cwd = str(Path.cwd())
    cmd = [
        "docker", "run", "--rm",
        "-v", "/var/run/docker.sock:/var/run/docker.sock",
        "-v", f"{cwd}:/work",
        "-w", "/work",
        f"aquasec/trivy:{sbom_cfg.get('trivyVersion', '0.50.0')}",
        "image",
        "--format", fmt,
        "--output", rel_out,
        image_ref,
    ]
    run(cmd)
    require(Path(rel_out).exists(), f"SBOM not produced at {rel_out}")
    log("INFO", f"SBOM written to {rel_out}")
    return rel_out


def minio_upload(file_path: str, minio_cfg: dict[str, Any]) -> str:
    endpoint = minio_cfg["endpoint"]
    bucket = minio_cfg["bucket"]
    object_key = minio_cfg["objectKey"]

    access = os.environ.get("MINIO_ACCESS_KEY")
    secret = os.environ.get("MINIO_SECRET_KEY")
    require(bool(access and secret),
            "MINIO_ACCESS_KEY / MINIO_SECRET_KEY env vars are not set; "
            "ensure withCredentials wraps the pythonBuild step")

    cwd = str(Path.cwd())
    alias = "sbomstore"
    mc_image = minio_cfg.get("mcImage", "minio/mc:latest")

    inner = (
        "set -eu;"
        f" mc alias set {alias} {shlex.quote(endpoint)} \"$MC_ACCESS\" \"$MC_SECRET\" >/dev/null;"
        f" mc mb --ignore-existing {alias}/{bucket};"
        f" mc cp {shlex.quote(file_path)} {alias}/{bucket}/{shlex.quote(object_key)}"
    )

    cmd = [
        "docker", "run", "--rm",
        "--network", "host",
        "-e", f"MC_ACCESS={access}",
        "-e", f"MC_SECRET={secret}",
        "-v", f"{cwd}:/work",
        "-w", "/work",
        "--entrypoint", "sh",
        mc_image,
        "-c", inner,
    ]
    run(cmd)
    log("INFO", f"Uploaded SBOM to {endpoint}/{bucket}/{object_key}")
    return f"{bucket}/{object_key}"


# ---------- main ------------------------------------------------------------ #

def main() -> int:
    ap = argparse.ArgumentParser(description="Jenkins shared lib build orchestrator")
    ap.add_argument("--config", required=True, help="Path to JSON config")
    ap.add_argument("--output", required=True, help="Path to write JSON output")
    args = ap.parse_args()

    with open(args.config, "r", encoding="utf-8") as f:
        cfg = json.load(f)

    log("INFO", f"Config: imageName={cfg.get('imageName')} tag={cfg.get('tag')} "
                f"registry={cfg.get('registry') or '-'}")

    image_ref = docker_build(cfg)

    sbom_path = trivy_sbom(image_ref, cfg.get("sbom") or {})

    minio_object = None
    if cfg.get("minio"):
        minio_object = minio_upload(sbom_path, cfg["minio"])

    out = {
        "imageRef": image_ref,
        "sbomPath": sbom_path,
        "minioObject": minio_object,
    }
    Path(args.output).write_text(json.dumps(out, indent=2))
    log("INFO", f"Build summary written to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
