#!/usr/bin/env python3
"""build-ota.py — build a LuckFox A/B OTA update (.swu) for SWUpdate.

Milestone M1. See OTA_SWUPDATE_PLAN.md.

What it does:
  1. (optional) builds the OTA image:  bitbake luckfox-image-minimal  (LUCKFOX_OTA=1)
  2. collects the rootfs ext4 image, kernel (zImage) and DTB from deploy/
  3. builds a small FAT boot image per slot (zImage + DTB + a slot-pinned
     extlinux.conf with root=PARTLABEL=rootfs_a|_b)
  4. emits an sw-description with two selections (stable,slot_a / stable,slot_b),
     each raw-writing rootfs.ext4 + the slot's boot image and flipping the U-Boot
     env (BOOT_ORDER + the slot's try-counter)
  5. packs everything into a CPIO (newc) .swu with sw-description FIRST

The update is slot-agnostic: the *device* picks the inactive slot at apply time:
    ota-apply update.swu              # on target (reads running slot, writes the other)
  or manually:
    swupdate -i update.swu -e stable,slot_b   # when slot A is running

M1 ships the .swu UNSIGNED (the boot chain isn't signed yet).

Usage:
    scripts/build-ota.py [--build] [--version X.Y.Z] [-o out.swu]
Run from the repo root (it sources the build env itself for --build / metadata).
"""

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
BUILD = REPO / "build"
IMAGE = "luckfox-image-minimal"

# A/B device map (must match meta-luckfox-bsp/wic/luckfox-rk3506-emmc-ab.wks).
SLOTS = {
    "slot_a": {"order": "A B", "ctr": "BOOT_A_LEFT", "rootfs": "/dev/mmcblk0p3", "boot": "/dev/mmcblk0p1", "partlabel": "rootfs_a"},
    "slot_b": {"order": "B A", "ctr": "BOOT_B_LEFT", "rootfs": "/dev/mmcblk0p4", "boot": "/dev/mmcblk0p2", "partlabel": "rootfs_b"},
}
MAX_TRIES = 3
BOOT_IMG_MB = 32           # FAT boot image size (>= content, < 64 MiB boot partition)
DTB_NAME = "rk3506b-luckfox-core3506.dtb"   # name extlinux's `fdt` line expects

KERNEL_APPEND = ("rootfstype=ext4 rootwait "
                 "earlycon=uart8250,mmio32,0xff0a0000,115200n8 "
                 "console=ttyFIQ0,115200 panic=10")


def run(cmd, **kw):
    print(f"  $ {cmd}")
    subprocess.run(cmd, shell=True, check=True, **kw)


def bb_vars(names):
    """Fetch bitbake variables via a single `bitbake -e luckfox-image-minimal`."""
    env_sh = REPO / "sources/poky/oe-init-build-env"
    out = subprocess.run(
        f"source {env_sh} {BUILD} >/dev/null 2>&1 && bitbake -e {IMAGE}",
        shell=True, check=True, text=True, capture_output=True, executable="/bin/bash",
    ).stdout
    vals = {}
    for line in out.splitlines():
        for n in names:
            if line.startswith(f"{n}="):
                vals[n] = line[len(n) + 1:].strip().strip('"')
    return vals


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build_boot_image(out_path, zimage, dtb, partlabel):
    """Build a FAT image holding zImage + DTB + a slot-pinned extlinux.conf."""
    extlinux = (
        f"default luckfox\nlabel luckfox\n"
        f"    kernel /zImage\n    fdt /{DTB_NAME}\n"
        f"    append root=PARTLABEL={partlabel} {KERNEL_APPEND}\n"
    )
    with tempfile.TemporaryDirectory() as td:
        cfg = Path(td) / "extlinux.conf"
        cfg.write_text(extlinux)
        run(f"dd if=/dev/zero of={out_path} bs=1M count={BOOT_IMG_MB} status=none")
        run(f"mkfs.vfat -n BOOT {out_path} >/dev/null")
        run(f"mmd -i {out_path} ::/extlinux")
        run(f"mcopy -i {out_path} {zimage} ::/zImage")
        run(f"mcopy -i {out_path} {dtb} ::/{DTB_NAME}")
        run(f"mcopy -i {out_path} {cfg} ::/extlinux/extlinux.conf")


def sw_description(version, files):
    """files: dict name->sha256. Emit libconfig sw-description with both slots."""
    def image(fn, dev):
        # installed-directly: stream straight to the device (hash verified
        # inline) instead of extracting to a temp dir first — the rootfs is
        # larger than tmpfs, so a temp copy fails with "Not enough free space".
        return (f'        {{ filename = "{fn}"; device = "{dev}"; '
                f'type = "raw"; installed-directly = true; '
                f'sha256 = "{files[fn]}"; }}')

    blocks = []
    for sel, s in SLOTS.items():
        boot_fn = f"boot-{sel[-1]}.vfat"
        blocks.append(f"""    {sel} = {{
      images: (
{image('rootfs.ext4', s['rootfs'])},
{image(boot_fn, s['boot'])}
      );
      bootenv: (
        {{ name = "BOOT_ORDER"; value = "{s['order']}"; }},
        {{ name = "{s['ctr']}"; value = "{MAX_TRIES}"; }}
      );
    }};""")
    return (f'software =\n{{\n'
            f'  version = "{version}";\n'
            f'  description = "LuckFox Core3506 A/B OTA (M1, unsigned)";\n'
            f'  hardware-compatibility = [ "1.0" ];\n'
            f'  stable = {{\n' + "\n".join(blocks) + "\n  };\n}\n")


def pack_swu(out_swu, srcdir, members):
    """CPIO newc, sw-description FIRST. swupdate streams it in order."""
    listing = "\n".join(members) + "\n"
    with open(out_swu, "wb") as f:
        subprocess.run("cpio -ov -H crc", shell=True, cwd=srcdir, check=True,
                       input=listing.encode(), stdout=f,
                       stderr=subprocess.DEVNULL)


def main():
    ap = argparse.ArgumentParser(description="Build a LuckFox A/B OTA .swu")
    ap.add_argument("--build", action="store_true", help="run bitbake first")
    ap.add_argument("--version", default=None, help="update version (default: DISTRO_VERSION+date)")
    ap.add_argument("-o", "--output", default=None, help="output .swu path")
    args = ap.parse_args()

    if args.build:
        print("== building image ==")
        run(f"bash -c 'source {REPO}/sources/poky/oe-init-build-env {BUILD} >/dev/null && "
            f"LUCKFOX_OTA=1 bitbake {IMAGE}'")

    print("== resolving deploy artifacts ==")
    v = bb_vars(["DEPLOY_DIR_IMAGE", "KERNEL_DEVICETREE", "MACHINE", "DISTRO_VERSION", "LUCKFOX_OTA"])
    if v.get("LUCKFOX_OTA") != "1":
        sys.exit("ERROR: LUCKFOX_OTA != 1 — set it in build/conf/local.conf (A/B layout + OTA tooling).")
    deploy = Path(v["DEPLOY_DIR_IMAGE"])
    dtb_src = deploy / Path(v["KERNEL_DEVICETREE"].split()[0]).name
    rootfs = deploy / f"{IMAGE}-{v['MACHINE']}.rootfs.ext4"
    zimage = deploy / "zImage"
    for p in (rootfs, zimage, dtb_src):
        if not p.exists():
            sys.exit(f"ERROR: missing artifact {p} (build the image first with --build)")

    version = args.version or f"{v['DISTRO_VERSION']}+{__import__('datetime').date.today():%Y%m%d}"
    out_swu = Path(args.output or (deploy / f"luckfox-ota-{version}.swu"))

    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        print("== staging payload ==")
        shutil.copy(rootfs, td / "rootfs.ext4")
        build_boot_image(td / "boot-a.vfat", zimage, dtb_src, "rootfs_a")
        build_boot_image(td / "boot-b.vfat", zimage, dtb_src, "rootfs_b")

        files = {n: sha256(td / n) for n in ("rootfs.ext4", "boot-a.vfat", "boot-b.vfat")}
        (td / "sw-description").write_text(sw_description(version, files))

        # sw-description MUST be first in the CPIO.
        members = ["sw-description", "rootfs.ext4", "boot-a.vfat", "boot-b.vfat"]
        print(f"== packing {out_swu} ==")
        pack_swu(out_swu, td, members)

    print(f"\nOK: {out_swu}  ({out_swu.stat().st_size // (1024*1024)} MiB)")
    print("Apply on target (writes the INACTIVE slot):")
    print("  ota-apply <file>.swu            # auto-selects the inactive slot")
    print("  # or: swupdate -i <file>.swu -e stable,slot_b   (when slot A is running)")


if __name__ == "__main__":
    main()
