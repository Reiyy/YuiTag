#!/usr/bin/env python3
"""
Download CoreADI
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

DMG_URL = (
    "https://secure-appldnld.apple.com/itunes12/041-56365-20210413-79A5AC6D-6B0A-46EC-B399-5A381183E3BE/iTunes12.8.3.dmg"
)
DMG_SHA256 = "dd08b9967db3e5847955fa168ce880303abf5f36de12abb2061e7ef1a25be214"
COREADI_SHA256 = "2eafc3c79c7f41a8afbfbeaaf23ee5dab5ad5bda55ec8e54dbc0ddf1d20ce630"
CPU_X86_64 = 0x01000007


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def find_7z() -> str:
    candidates = [
        "7z",
        "7za",
        "/usr/bin/7z",
        "/usr/local/bin/7z",
        r"C:\Program Files\7-Zip\7z.exe",
        r"C:\Program Files (x86)\7-Zip\7z.exe",
    ]
    for c in candidates:
        found = shutil.which(c)
        if found:
            return found
        if os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    raise FileNotFoundError(
        "7z executable not found on PATH. Please install p7zip (e.g. `sudo apt-get install p7zip-full` or 7-Zip on Windows)."
    )


def download_file(url: str, dest: Path, expected_sha256: str) -> None:
    if dest.exists() and sha256_file(dest) == expected_sha256:
        print(f"[CoreADI] Cached file verified: {dest} ({expected_sha256[:12]}...)")
        return

    print(f"[CoreADI] Downloading from Apple CDN: {url}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    temp_dest = dest.with_suffix(".download.tmp")

    req = urllib.request.Request(
        url,
        headers={"User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"},
    )
    with urllib.request.urlopen(req) as resp, open(temp_dest, "wb") as out:
        total_size = int(resp.headers.get("Content-Length", 0))
        downloaded = 0
        last_pct = -1
        chunk_size = 1024 * 1024

        while True:
            chunk = resp.read(chunk_size)
            if not chunk:
                break
            out.write(chunk)
            downloaded += len(chunk)
            if total_size > 0:
                pct = int((downloaded / total_size) * 100)
                if pct != last_pct and pct % 10 == 0:
                    print(f"[CoreADI] Download progress: {pct}% ({downloaded // (1024 * 1024)}MB / {total_size // (1024 * 1024)}MB)")
                    last_pct = pct

    print("[CoreADI] Download complete. Verifying SHA-256...")
    actual_sha = sha256_file(temp_dest)
    if actual_sha != expected_sha256:
        temp_dest.unlink(missing_ok=True)
        raise ValueError(
            f"Downloaded DMG SHA-256 mismatch!\nExpected: {expected_sha256}\nGot:      {actual_sha}"
        )

    if dest.exists():
        dest.unlink()
    temp_dest.rename(dest)
    print(f"[CoreADI] Verified SHA-256: {actual_sha}")


def unpack_cpio(data: bytes, out_dir: Path) -> list[Path]:
    """Extract an Apple odc or newc cpio archive into out_dir."""
    FORMATS = {
        b"070707": ([6, 6, 6, 6, 6, 6, 6, 11, 6, 11], 2, 8, 9, False),
        b"070701": ([8] * 13, 1, 11, 6, True),
        b"070702": ([8] * 13, 1, 11, 6, True),
    }
    S_IFMT, S_IFDIR, S_IFLNK = 0o170000, 0o040000, 0o120000
    written: list[Path] = []
    seen_inodes: dict[tuple[int, ...], Path] = {}
    pos = 0

    while pos < len(data) - 6:
        magic = data[pos : pos + 6]
        if magic not in FORMATS:
            break
        widths, mode_idx, namesize_idx, filesize_idx, padded = FORMATS[magic]
        pos += 6
        fields = []
        for width in widths:
            fields.append(int(data[pos : pos + width], 8))
            pos += width
        mode = fields[mode_idx]
        namesize = fields[namesize_idx]
        filesize = fields[filesize_idx]
        name = data[pos : pos + namesize - 1].decode("utf-8", errors="replace")
        data_start = pos + namesize
        if padded:
            data_start = (data_start + 3) & ~3
        if name == "TRAILER!!!":
            break
        next_pos = data_start + filesize
        if padded:
            next_pos = (next_pos + 3) & ~3
        if name in ("", "."):
            pos = next_pos
            continue
        target = out_dir / name
        if mode & S_IFMT == S_IFDIR:
            target.mkdir(parents=True, exist_ok=True)
        elif mode & S_IFMT == S_IFLNK:
            target.parent.mkdir(parents=True, exist_ok=True)
        else:
            inode_key = (fields[0], fields[1]) if magic == b"070707" else (fields[0], fields[7], fields[8])
            if filesize == 0 and inode_key in seen_inodes:
                pass
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data[data_start : data_start + filesize])
                seen_inodes[inode_key] = target
                written.append(target)
        pos = next_pos
    return written


def extract_x86_64_slice(universal_mach_o: bytes) -> bytes:
    """Extract the x86_64 slice from an Apple Universal Mach-O fat binary."""
    if universal_mach_o[:4] not in (b"\xca\xfe\xba\xbe", b"\xca\xfe\xba\xbf"):
        raise ValueError("File is not a valid Universal Mach-O fat binary")
    nfat = struct.unpack(">I", universal_mach_o[4:8])[0]
    for i in range(nfat):
        cpu, _, off, size, _ = struct.unpack(
            ">IIIII", universal_mach_o[8 + 20 * i : 28 + 20 * i]
        )
        if cpu == CPU_X86_64:
            return universal_mach_o[off : off + size]
    raise ValueError("No x86_64 slice found in Universal Mach-O binary")


def extract_coreadi(dest_dir: Path, cache_dir: Path | None = None) -> Path:
    out_file = dest_dir / "CoreADI.x86_64"
    if out_file.exists() and sha256_file(out_file) == COREADI_SHA256:
        print(f"[CoreADI] Output already exists and matches expected SHA-256: {out_file}")
        return out_file

    seven_zip = find_7z()
    print(f"[CoreADI] Using 7z: {seven_zip}")

    if cache_dir is None:
        work_dir = Path(tempfile.mkdtemp(prefix="coreadi_extract_"))
        cleanup_work = True
    else:
        work_dir = cache_dir
        work_dir.mkdir(parents=True, exist_ok=True)
        cleanup_work = False

    try:
        dmg_path = work_dir / "iTunes12.8.3.dmg"
        download_file(DMG_URL, dmg_path, DMG_SHA256)

        ipkg_dir = work_dir / "ipkg"
        if not (ipkg_dir / "iTunes/Install iTunes.pkg").exists():
            print("[CoreADI] Extracting 'Install iTunes.pkg' from DMG...")
            subprocess.run(
                [seven_zip, "x", str(dmg_path), "iTunes/Install iTunes.pkg", f"-o{ipkg_dir}", "-y"],
                check=True,
                stdout=subprocess.DEVNULL,
            )

        pkg_dir = work_dir / "coreadi_pkg"
        if not (pkg_dir / "CoreADI.pkg/Payload").exists():
            print("[CoreADI] Extracting 'CoreADI.pkg/Payload' from pkg...")
            subprocess.run(
                [
                    seven_zip,
                    "x",
                    str(ipkg_dir / "iTunes/Install iTunes.pkg"),
                    "CoreADI.pkg/Payload",
                    f"-o{pkg_dir}",
                    "-y",
                ],
                check=True,
                stdout=subprocess.DEVNULL,
            )

        payload_path = pkg_dir / "CoreADI.pkg/Payload"
        print("[CoreADI] Decompressing gzip payload...")
        cpio_bytes = gzip.decompress(payload_path.read_bytes())

        root_dir = work_dir / "rootfs"
        framework_rel = Path("System/Library/PrivateFrameworks/CoreADI.framework/Versions/A/CoreADI")
        framework_path = root_dir / framework_rel

        if not framework_path.exists():
            print("[CoreADI] Unpacking cpio archive...")
            unpack_cpio(cpio_bytes, root_dir)

        if not framework_path.exists():
            # Fallback: search for CoreADI binary anywhere under rootfs
            matches = list(root_dir.glob("**/CoreADI"))
            if matches:
                framework_path = matches[0]
            else:
                raise FileNotFoundError(f"Could not locate CoreADI framework binary under {root_dir}")

        print(f"[CoreADI] Found framework binary at: {framework_path}")
        fat_binary = framework_path.read_bytes()

        print("[CoreADI] Extracting x86_64 slice from Mach-O fat binary...")
        slice_bytes = extract_x86_64_slice(fat_binary)

        slice_hash = hashlib.sha256(slice_bytes).hexdigest()
        if slice_hash != COREADI_SHA256:
            raise ValueError(
                f"Extracted x86_64 slice hash mismatch!\nExpected: {COREADI_SHA256}\nGot:      {slice_hash}"
            )

        dest_dir.mkdir(parents=True, exist_ok=True)
        out_file.write_bytes(slice_bytes)
        print(f"[CoreADI] Successfully written to: {out_file} ({len(slice_bytes)} bytes, SHA-256: {slice_hash})")
        return out_file

    finally:
        if cleanup_work:
            shutil.rmtree(work_dir, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Extract Apple CoreADI x86_64 slice from official iTunes DMG")
    parser.add_argument(
        "--dest",
        type=Path,
        default=Path("App/app/src/main/assets/coreadi"),
        help="Target output directory for CoreADI.x86_64",
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=None,
        help="Optional cache directory to keep downloaded iTunes12.8.3.dmg",
    )
    args = parser.parse_args()

    try:
        extract_coreadi(dest_dir=args.dest, cache_dir=args.cache_dir)
        return 0
    except Exception as e:
        print(f"[CoreADI] Error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
