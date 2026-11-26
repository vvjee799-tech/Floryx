"""Restore the exact bundled runtime from its checked-in SHA-256 manifest.

Python 3.10+, standard library only. No archives are extracted to arbitrary paths.
Usage: python tools/prepare_terminal_runtime.py --download-dir D:/download/FlowChat-runtime
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/terminal"
ABIS = {"aarch64": "arm64-v8a", "x86_64": "x86_64", "arm": "armeabi-v7a", "i686": "x86"}


def fetch(item, downloads):
    path = downloads / item["url"].rsplit("/", 1)[-1]
    if not path.exists():
        with urllib.request.urlopen(item["url"], timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != item["sha256"]:
            raise ValueError(f"Checksum mismatch: {path.name}")
        path.write_bytes(data)
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != item["sha256"]:
        raise ValueError(f"Checksum mismatch: {path.name}; remove the cached file and retry")
    return data


def deb_data(data):
    if data[:8] != b"!<arch>\n":
        raise ValueError("Invalid Debian package")
    offset = 8
    while offset + 60 <= len(data):
        header = data[offset:offset + 60]
        size = int(header[48:58])
        body = data[offset + 60:offset + 60 + size]
        offset += 60 + size + size % 2
        if header[:16].decode().strip().startswith("data.tar"):
            return tarfile.open(fileobj=io.BytesIO(body), mode="r:*")
    raise ValueError("Package has no data archive")


def package_inventory(manifest):
    result = {}
    fields = {"P": "name", "V": "version", "A": "architecture", "L": "license", "o": "origin", "c": "aportsCommit", "U": "homepage"}
    for abi, item in manifest["linux"].items():
        with tarfile.open(ASSETS / item["asset"], mode="r:gz") as archive:
            member = next(m for m in archive if m.name.endswith("/apk/db/installed"))
            database = archive.extractfile(member).read().decode()
        result[abi] = [{fields[line[0]]: line[2:] for line in record.splitlines() if len(line) > 2 and line[1] == ":" and line[0] in fields} for record in database.strip().split("\n\n")]
    (ASSETS / "alpine-packages.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download-dir", type=Path, default=Path("D:/download/FlowChat-runtime") if os.name == "nt" else ROOT / "build/runtime-downloads")
    args = parser.parse_args()
    args.download_dir.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((ASSETS / "runtime-manifest.json").read_text(encoding="utf-8"))
    for item in manifest["linux"].values():
        (ASSETS / item["asset"]).write_bytes(fetch(item, args.download_dir))
        print("Verified", item["asset"])
    for item in manifest["packages"]:
        destination = ROOT / "app/src/main/jniLibs" / ABIS[item["architecture"]]
        destination.mkdir(parents=True, exist_ok=True)
        with deb_data(fetch(item, args.download_dir)) as archive:
            for entry in archive:
                if not entry.isfile():
                    continue
                name = entry.name
                target = None
                if item["name"] == "proot":
                    target = {"proot": "libflowchat_proot.so", "loader": "libflowchat_loader.so", "loader32": "libflowchat_loader32.so"}.get(name.rsplit("/", 1)[-1])
                elif item["name"] == "libtalloc" and "/lib/libtalloc.so." in name:
                    target = "libflowchat_talloc.so"
                elif item["name"] == "libandroid-shmem" and name.endswith("/lib/libandroid-shmem.so"):
                    target = "libandroid-shmem.so"
                if target:
                    (destination / target).write_bytes(archive.extractfile(entry).read())
        print("Verified", item["name"], item["architecture"], item["version"])
    package_inventory(manifest)


if __name__ == "__main__":
    main()
