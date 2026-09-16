# OTA Update Plan — SWUpdate A/B (Milestone M1)

Concrete implementation plan for over-the-air updates on the Core3506 + Foxbridge
platform, using **SWUpdate** with a dual-copy (A/B) rootfs + boot scheme and
U-Boot rollback. This is the *how* for milestone **M1** in
the platform constraints in the *Key constraints* section of the README, which
shape every decision below. Created 2026-05-26.

## Scope

**In scope (M1):**
- Atomic, power-fail-safe update of **rootfs** and **boot** (kernel + DTB +
  extlinux) via two slots (A/B) on eMMC.
- **Automatic rollback** to the previous slot if the new one fails to boot or
  fails its post-boot health check.
- Userspace slot control via **libubootenv** (`fw_setenv`/`fw_printenv`).
- **Local delivery**: operator pushes a `.swu` via SWUpdate's built-in web
  UI / REST endpoint (or `swupdate -i` over SSH). No server infrastructure.

**Out of scope (documented as next steps):**
- **Signed `.swu`** (M2) — only meaningful once the boot chain is signed
  M1 ships unsigned; dev keys (`keys/fit/`) exist for when
  M2 lands on the secure-boot branch.
- **Recovery slot** (M3).
- **The bootloader is NOT OTA-upgradeable** — hard platform constraint, see callout below.
- **Managed/remote rollout** (hawkBit via Suricatta) — the eventual model; M1 is
  built so this drops in later (see [Remote delivery later](#remote-delivery-later)).

> ### ⚠️ The bootloader cannot be updated over OTA
>
> On the RK3506 the **BootROM loads `idbloader.img` and `u-boot.img` from
> hardcoded raw flash offsets** (32 KiB and 8 MiB). They cannot be moved, and there
> is no room for a second copy the BootROM would fall back to — so **there is no
> A/B for the bootloader, and no safe atomic way to replace it in the field.** Any
> rewrite of those offsets is a non-atomic operation with a real brick window if
> power is lost mid-write.
>
> Therefore, for OTA on this platform:
> - **What OTA updates:** the rootfs and the boot files (kernel + DTB + extlinux),
>   atomically, via the A/B slots.
> - **What OTA does NOT touch:** `idbloader.img`, `u-boot.img`, and the **OP-TEE
>   blob bundled inside `u-boot.img`**. These are fixed at
>   first flash and only change via a full reflash over maskrom/USB (physical
>   access).
> - **Nuance — the U-Boot *environment*:** OTA *does* write a few U-Boot **env
>   variables** (the A/B slot pointer + try-counters) in the dedicated `uboot-env`
>   partition. That is the intended slot-switch mechanism — a small, bounded write,
>   not a replacement of the bootloader binary. Don't confuse "writing env vars" with
>   "updating the bootloader"; only the former happens.
>
> Updating the loader at all (with verify + watchdog to shrink the brick window) is
> milestone **M4**, a separate effort that is never fully brick-safe on this SoC —
> out of scope here.

## Why SWUpdate

Briefly: small C daemon, mature
`meta-swupdate` layer, flexible `sw-description` (raw/partition/bootloader-env
handlers cover our needs), built-in local web UI for M1, and a Suricatta client for
the later hawkBit path — so the same framework spans local→managed without a
re-architecture. RAUC/Mender would also work; SWUpdate wins on footprint + the
local→remote continuity.

## Current baseline (what exists today)

From the repo audit (all on `main`):
- **Single rootfs**, RW ext4, label `rootfs`; no A/B, no dm-verity, no overlay.
- **Single boot** partition (FAT32, label `boot`), extlinux.conf with
  `root=LABEL=rootfs`.
- **U-Boot 2017.09 vendor fork** — `distro_bootcmd` fallback patched in, but **no
  bootcount/altbootcmd**, and the env persistence location is **unverified**.
- eMMC layout: idbloader @ 32 KiB, u-boot.img @ 8 MiB, boot @ 16 MiB, rootfs @
  24 MiB (`meta-luckfox-bsp/wic/luckfox-rk3506-emmc.wks`).
- No `meta-swupdate`, no `libubootenv` in the image.

Everything in M1 is **new build-out**. None of it conflicts with the secure-boot /
dm-verity work on the `core3506-optee-secure-boot` branch (see
[Integration with secure boot](#integration-with-secure-boot--verity)).

## Target partition layout

The eMMC is 8 GB. Device is `mmcblk0`.

| Region | Offset / size | Device | Label / use |
|--------|------|--------|-------------|
| idbloader + u-boot.img | 32 KiB / 8 MiB (u-boot @8 MiB, ~0.8 MiB actual) | raw, fixed offsets | single copy — **not** OTA-able |
| **U-Boot env** | **12 MiB, 32 KiB** (single copy for M1; redundant deferred) | raw (no GPT entry) | A/B slot state — lives in the 8–16 MiB loader gap, **not** a partition |
| boot_a | 64 MiB | `mmcblk0p1` | FAT32, kernel+DTB+extlinux (slot A) |
| boot_b | 64 MiB | `mmcblk0p2` | FAT32 (slot B) |
| rootfs_a | 512 MiB | `mmcblk0p3` | ext4 (slot A) |
| rootfs_b | 512 MiB | `mmcblk0p4` | ext4 (slot B) |
| recovery | 256 MiB | `mmcblk0p5` | reserved for M3 (create now, leave empty) |
| data | remainder (~6.5 GiB) | `mmcblk0p6` | persistent `/data` — survives updates, OTA staging |

Boot slot and rootfs slot are **paired** (boot_a → rootfs_a). The selected boot
partition's `extlinux.conf` carries the matching `root=PARTLABEL=rootfs_a|_b`.

> **Migration caveat:** this is a flash-layout change. Fielded units on the current
> single-slot layout **cannot OTA into it** — the first move to A/B requires a full
> reflash (maskrom/USB). After that, all rootfs+boot updates are OTA. Plan the
> layout to be final-ish before first field deployment.

## Boot-slot selection + rollback (U-Boot) — the critical path

This is the hard part. The vendor fork lacks bootcount/altbootcmd,
so we implement A/B selection with **U-Boot env variables + a boot script**, riding
on the already-enabled `distro_bootcmd`. (Standard U-Boot A/B `boot.scr` pattern —
no deep C surgery.)

> ### ⚠️ Finding (2026-05-27) — the DTB pinned `root=` and overrode everything
>
> First A/B boot panicked: `VFS: Unable to mount root fs on unknown-block(179,2)`
> (=mmcblk0p2) despite extlinux passing `root=PARTLABEL=rootfs_a`. Two coupled
> causes (now fixed; see [[reference_dtb_root_override]]):
> - **`rk3506b-luckfox-core3506.dtsi` `/chosen/bootargs` hardcoded
>   `root=/dev/mmcblk0p2`.** Rockchip U-Boot `board_fdt_chosen_bootargs()` appends
>   the DTB cmdline **after** the bootloader's, and the kernel takes the **last**
>   `root=` → the DTB always won. Removed `root=` from the DTB so the bootloader
>   owns it. The old single-slot image only worked because its rootfs *was* p2.
> - **This kernel doesn't resolve `root=LABEL=`** (fs label) — only `PARTLABEL=` /
>   `PARTUUID=`. So A/B uses `root=PARTLABEL=rootfs_a|_b` (GPT names); the
>   single-slot extlinux was switched from `root=LABEL=rootfs` to
>   `root=/dev/mmcblk0p2`.

**Env variables (in the dedicated `uboot-env` partition):**
- `BOOT_ORDER` — e.g. `"A B"` (try A first) or `"B A"`.
- `BOOT_A_LEFT`, `BOOT_B_LEFT` — remaining boot attempts per slot (e.g. start at 3).

> ### Implementation note (2026-05-27) — env script, not a boot.scr file
>
> The selector is an **env variable `ab_select`** run via `bootcmd=run ab_select`
> (exactly how `distro_bootcmd` works), **not** a compiled `boot.scr`. Reason: this
> vendor U-Boot 2017.09's `source` mishandles the mkimage `-T script` container —
> it aborts with `Unknown command 'I'` regardless of the script body. An env script
> run with `run` is robust. `ab_select` is seeded onto the device by
> `ota-env-init` from `/etc/ota-bootenv` (both shipped by the `ota-bootmgr` recipe).
> Counter decrement uses `setexpr`, which the vendor defconfig omits — enabled via
> `CONFIG_CMD_SETEXPR=y` in `rk3506-distroboot.config`.

**Selector algorithm (`ab_select`):**
1. For each slot in `BOOT_ORDER`:
   - if `BOOT_<slot>_LEFT > 0`: decrement it (`setexpr`), `saveenv`, then
     `sysboot` that slot's boot partition `/extlinux/extlinux.conf` (which pins
     `root=PARTLABEL=rootfs_a|_b`).
   - else: continue to next slot.
2. If no slot boots → fall back to `distro_bootcmd` (recovery slot is M3).

**Mark-good (userspace, post-boot):** a systemd service confirms the new slot is
healthy (network up, key services started — configurable) and resets that slot's
`BOOT_<slot>_LEFT` to the max and sets `BOOT_ORDER` to prefer it. If the system
hangs/panics before mark-good, the attempt counter is already decremented, so the
next boot retries and eventually falls back to the known-good slot. This gives
power-fail-safe rollback without watchdog dependence (a watchdog further tightens
the hang case — recommended, see open questions).

> ### Finding (2026-05-26) — env is NOT persistent today
>
> Inspected the built U-Boot `.config`
> (`build/tmp-glibc/work/.../u-boot-rockchip-rk3506/2017.09/git/.config`):
> ```
> CONFIG_ENV_SIZE=0x8000
> CONFIG_CMD_SAVEENV=y
> # CONFIG_ENV_IS_IN_MMC is not set   (every CONFIG_ENV_IS_IN_* is unset)
> # CONFIG_BOOTCOUNT is not set
> ```
> So the env defaulted to **`ENV_IS_NOWHERE`** — RAM-only, `saveenv` did not persist,
> and there was no bootcount. **The A/B rollback mechanism had no foundation.**
>
> **Done (2026-05-26):** added `rk3506-env-mmc.config` (wired into the u-boot recipe)
> enabling `CONFIG_ENV_IS_IN_MMC` at `CONFIG_ENV_OFFSET=0xC00000` (12 MiB, in the
> 8–16 MiB loader gap — `u-boot.img` is only ~0.8 MiB, so the gap is wide open),
> `CONFIG_ENV_SIZE=0x8000`. Rebuilt and **verified in the resulting `.config`**:
> `CONFIG_ENV_IS_IN_MMC=y`, `CONFIG_ENV_OFFSET=0xC00000`, `ENV_IS_NOWHERE` now unset.
> Env device defaults to mmc dev 0 (boot eMMC) via `env/mmc.c`.
>
> **Deferred — redundant env:** `CONFIG_SYS_REDUNDAND_ENVIRONMENT` /
> `CONFIG_ENV_OFFSET_REDUND` are header-driven (not Kconfig) in this fork, so the
> fragment can't enable them; needs a board-header patch. Single env is fine for M1
> (a corrupt env falls back to the built-in default = boots slot A, not a brick).
> Track as M1 hardening.
>
> **HW-VERIFIED (2026-05-26):** flashed an `LUCKFOX_OTA=1` image and confirmed the
> full loop on hardware:
> - U-Boot `saveenv` → `Saving Environment to MMC... Writing to MMC(0)... done`.
> - Linux `fw_setenv ota_test …` writes 0xC00000; `fw_printenv` reads it back.
> - **Survives a hardware reset** (relay RESET): `ota_test` persisted, and
>   **U-Boot `printenv ota_test` read the value Linux wrote** ⇒ U-Boot ↔ Linux share
>   one env. The A/B-rollback env foundation works end-to-end.
>
> **CRITICAL gap found — ship the FULL `/etc/u-boot-initial-env`.** `fw_setenv`
> won't bootstrap a blank/invalid env without a default (`-f`, default
> `/etc/u-boot-initial-env`); on a freshly-flashed device the env region is blank,
> so the image MUST ship that file. **It must be U-Boot's complete default env**
> (use `UBOOT_INITIAL_ENV` in the u-boot recipe to generate it) — a hand-written
> *stub* is dangerous: seeding only a couple of vars and writing them via `fw_setenv`
> *replaces* U-Boot's default env with the stub, so U-Boot loses `bootdelay` /
> `distro_bootcmd` and **drops to the `=>` prompt instead of autobooting** (hit
> exactly this in testing; recovered with `env default -a; saveenv`).
>
> **DONE (2026-05-27):** the u-boot recipe now runs the Makefile's
> `u-boot-initial-env` target in `do_compile` (objcopy-dumps the full
> `.rodata.default_environment` from the freshly-built `u-boot.bin`) and ships it
> to the rootfs as `/etc/u-boot-initial-env` via a dedicated split package
> `u-boot-rockchip-rk3506-env`, gated into the image alongside `libubootenv-bin`
> behind `LUCKFOX_OTA`. This recipe does **not** inherit `u-boot.inc`, so the
> standard `UBOOT_INITIAL_ENV` machinery doesn't fire — wired in explicitly.
>
> **HW-VERIFIED (2026-05-27):** flashed the `LUCKFOX_OTA=1` screen image and ran
> the full blank-env path on hardware:
> - The wic write zeroes the env region (12 MiB gap is zero-filled in the image),
>   so the freshly-flashed unit is genuinely blank — `fw_printenv` →
>   *"Cannot read environment, using default"*, and U-Boot **autoboots** off its
>   compiled-in default (distro_bootcmd → extlinux → kernel → login).
> - `/etc/u-boot-initial-env` on-device = the full 43-line env (`bootcmd`,
>   `bootdelay`, `distro_bootcmd`, …).
> - `fw_setenv -f /etc/u-boot-initial-env` seeds; `fw_printenv bootcmd/bootdelay`
>   read back full.
> - **The dangerous case is proven safe:** after `fw_setenv BOOT_ORDER "A B"`,
>   `bootcmd` is *still* intact (no stub-replacement), and after a reboot the unit
>   **still autoboots** (countdown not stopped, no drop to `=>`) with
>   `BOOT_ORDER=A B` persisted ⇒ seeded full env survives in eMMC and U-Boot↔Linux
>   share it. Task 1 closed.
>
> **Resolved — bootcount.** We do NOT use `CONFIG_BOOTCOUNT`; the try-counters
> (`BOOT_A_LEFT`/`BOOT_B_LEFT`) are script-managed in the `ab_select` env script
> via `setexpr` (`CONFIG_CMD_SETEXPR` enabled). No C backport needed.

**Other prerequisites:**
- The `uboot-env` raw location **must** be one both U-Boot and `fw_setenv` agree on
  (`/etc/fw_env.config`).
- Optional: backport `CONFIG_BOOTCOUNT_LIMIT` for a cleaner counter (alternative to
  the script-managed counters).

## Userspace components

- **libubootenv** (`libubootenv-bin`, RPROVIDES `u-boot-fw-utils`): provides
  `fw_printenv` / `fw_setenv`. **DONE (2026-05-26):** `libubootenv_%.bbappend` in
  `meta-luckfox-distro/recipes-bsp/libubootenv/` ships `/etc/fw_env.config`
  mirroring the U-Boot env (single line, single env copy for M1):
  ```
  # device        offset      env-size   sector-size (eMMC 512B)
  /dev/mmcblk0    0xC00000    0x8000     0x200
  # /dev/mmcblk0  0xC10000    0x8000     0x200   <- redundant copy (deferred)
  ```
  Offsets match `rk3506-env-mmc.config` (`CONFIG_ENV_OFFSET=0xC00000`,
  `CONFIG_ENV_SIZE=0x8000`). Added to the image behind the `LUCKFOX_OTA` gate
  (see below). This puts `fw_printenv` on the board so the env `saveenv` round-trip
  is testable as soon as the env-in-MMC u-boot is flashed.
- **swupdate**: the daemon + `swupdate-www` (local web UI) for M1. Configured with
  the partition/raw + bootloader-env handlers; signature verification **off** for M1.
- **mark-good service**: oneshot systemd unit, after `multi-user.target`, runs the
  health check then commits the slot via `fw_setenv`.

## Yocto integration (as-built)

1. **Layers — DONE.** `meta-openembedded/meta-oe` (for `libconfig`) + `meta-swupdate`
   cloned into `sources/`, pinned in `setup-layers.json`, added to `bblayers`
   (`bblayers.conf.sample`). Both on `scarthgap`.
2. **swupdate config — DONE.** `recipes-support/swupdate/swupdate_%.bbappend` +
   `swupdate-ota.cfg` fragment on top of meta-swupdate's defconfig. Enabled:
   `CONFIG_BOOTLOADERHANDLER` (the `uboot`/`bootenv` install handler),
   `CONFIG_BOOTLOADER_STATIC_LINKED`, `CONFIG_HASH_VERIFY`, `CONFIG_ARCHIVE`
   (defconfig already had raw + `CONFIG_UBOOT` env backend wired to
   `/etc/fw_env.config` + `/etc/u-boot-initial-env`, + Mongoose web UI);
   `CONFIG_SIGNED_IMAGES` stays off (M1). bbappend also `RDEPENDS += libgcc`.
   (The exact errors each of these clears are in the [work breakdown](#work-breakdown)
   row 4 / the swupdate-config notes.)
3. **Image install — DONE.** Gated behind `LUCKFOX_OTA = "1"` in
   `luckfox-image-minimal.inc`: `libubootenv-bin u-boot-rockchip-rk3506-env
   ota-bootmgr swupdate swupdate-www`.
4. **`.swu` artifact — DONE, via `scripts/build-ota.py`** (a standalone Python
   packer), **not** a `luckfox-image-swu.bb` / `inherit swupdate` recipe — the
   script is simpler to iterate and keeps the `sw-description` in readable code.
   It bundles `rootfs.ext4` (raw, slot-agnostic) + a per-slot FAT boot image
   (`boot-a/b.vfat`: zImage+DTB+slot-pinned extlinux) and emits the dual-slot
   `sw-description`. See [sketch](#sw-description-sketch).
5. **WKS — DONE.** `luckfox-rk3506-emmc-ab.wks` (the [layout above](#target-partition-layout));
   the single-slot `luckfox-rk3506-emmc.wks` is kept for non-OTA builds.
   `WKS_FILE` switches on `LUCKFOX_OTA` in `luckfox-image-minimal.bb`.
6. **Versioning:** `build-ota.py` stamps the `.swu` `version` from `DISTRO_VERSION`
   + date; `hardware-compatibility = ["1.0"]` is matched against `/etc/hwrevision`
   (`foxbridge 1.0`, shipped by `ota-bootmgr`). A richer `/etc/sw-versions` is a
   nice-to-have, not yet done.
7. **Per-slot boot config — DONE (in `build-ota.py`, not wic templates).** Each
   slot's FAT boot image carries an `extlinux.conf` pinned to
   `root=PARTLABEL=rootfs_a|_b`; `extlinux-slot-a.conf` is the first-flash slot-A
   copy.

## `sw-description` sketch (as generated by `build-ota.py`)

Two named selections (`stable.slot_a` / `stable.slot_b`); the **device** picks the
inactive one at apply time (`ota-apply` reads the running slot from `/proc/cmdline`
and selects the other). Both reference the same slot-agnostic `rootfs.ext4` plus a
per-slot boot image. Images are written **raw** and **`installed-directly`**
(streamed to the device — the rootfs is larger than tmpfs, so no temp extraction),
sha256-verified inline; `bootenv` flips the slot pointer + try-counter.

```
software = {
  version = "0.2.0";
  hardware-compatibility = [ "1.0" ];   /* matched vs /etc/hwrevision "foxbridge 1.0" */
  stable = {
    slot_b = {   /* selected by `ota-apply` when slot A is running */
      images: (
        { filename="rootfs.ext4"; device="/dev/mmcblk0p4"; type="raw"; installed-directly=true; sha256=...; },
        { filename="boot-b.vfat"; device="/dev/mmcblk0p2"; type="raw"; installed-directly=true; sha256=...; }
      );
      bootenv: ( {name="BOOT_ORDER"; value="B A";}, {name="BOOT_B_LEFT"; value="3";} );
    };
    slot_a = { /* mirror: rootfs->p3, boot-a.vfat->p1, BOOT_ORDER="A B", BOOT_A_LEFT=3 */ };
  };
};
```

The `bootenv` writes go through SWUpdate's `uboot` handler (static-linked,
`CONFIG_BOOTLOADERHANDLER`) → the shared U-Boot env @12 MiB → read by the
`ab_select` env script on the next boot.

## Update flow (local, M1) — as-built & HW-verified

1. Build the `.swu`: `scripts/build-ota.py --build` (or `--version X.Y.Z`).
2. Get it onto the device + `ota-apply update.swu` (reads the running slot, runs
   `swupdate -i … -e stable,slot_<inactive>`). The Mongoose web UI
   (`http://<ip>:8080`) is the alternative upload path.
3. SWUpdate streams the inactive rootfs+boot slot, verifies sha256, sets
   `BOOT_ORDER`/try-count to the new slot, returns success.
4. Reboot → the `ab_select` env script boots the new slot (decrementing its counter).
5. `ota-mark-good` confirms health → commits the slot. On failure, U-Boot rolls back
   automatically (counter exhausts → other slot).

> **Transfer note (M1, no infra):** the `.swu` is ~370 MiB. ICMP host→board is
> filtered on this board, but board→host works — serve it from a host
> `python3 -m http.server` and `wget` it from the board into `/data` (`mmcblk0p6`),
> which has room (rootfs free space alone does not). HW test used this.

## Build & hardware test plan

1. **Build**: `.wic` (A/B layout) for first flash + the `.swu` for updates.
2. **First flash**: maskrom/USB the A/B `.wic`; confirm it boots slot A, both
   rootfs slots present, env partition readable by `fw_printenv`.
3. **Happy-path update**: push a `.swu` with a visible change (e.g. bumped
   `/etc/sw-versions`); confirm reboot lands in slot B and mark-good commits it.
4. **Rollback test**: push a deliberately-broken slot (e.g. corrupt kernel or a
   mark-good that fails); confirm U-Boot exhausts the counter and falls back to the
   known-good slot. Drive/observe over the **serial console** (UART0 / `/dev/ttyACM0`
   — see `DEV_SETUP.md`).
5. **Power-fail test**: yank power mid-write; confirm the running slot is untouched
   and still boots.

## Work breakdown

| # | Task | Effort | Risk |
|---|------|--------|------|
| 1 | ~~Persistent env-in-MMC + ship `/etc/u-boot-initial-env` + on-HW blank-env seed/autoboot check~~ **DONE + HW-VERIFIED 2026-05-27** (`rk3506-env-mmc.config` env @12 MiB; `u-boot-initial-env` generated + shipped via `-env` split pkg; blank-env seed + slot-write + reboot autoboot all confirmed on hardware) | — | closed |
| 2 | ~~A/B slot/rollback selector~~ **DONE + HW-VERIFIED 2026-05-27** — `ab_select` **env script** (`bootcmd=run ab_select`) iterates `BOOT_ORDER`, decrements `BOOT_x_LEFT` (`setexpr`, enabled via `CONFIG_CMD_SETEXPR`), saveenv's, sysboots the slot. NOT a boot.scr file: this U-Boot's `source` mishandles the mkimage script container ("Unknown command 'I'"). Seeded by `ota-bootmgr`/`ota-env-init`. | — | closed |
| 3 | ~~A/B WKS + per-slot extlinux~~ **DONE + HW-VERIFIED 2026-05-27** — `luckfox-rk3506-emmc-ab.wks` (6-part GPT, 512 MiB slots, `--part-name` PARTLABELs); `extlinux-slot-a.conf` pins `root=PARTLABEL=rootfs_a`. Empty B/recovery reserved via `--fstype=none`. | — | closed |
| 4 | ~~`meta-swupdate` integration~~ **DONE + HW-VERIFIED 2026-05-27** — meta-oe (libconfig) + meta-swupdate in `sources/` (pinned in `setup-layers.json`) + bblayers. The default defconfig is **insufficient** for A/B-with-bootenv+hash; the `swupdate_%.bbappend` fragment adds (all needed, each found on HW): `CONFIG_BOOTLOADERHANDLER` (registers the `uboot`/`bootenv` handler — without it: *"bootloader support absent…"*), `CONFIG_BOOTLOADER_STATIC_LINKED`, `CONFIG_HASH_VERIFY` (sha256 via OpenSSL — without it: *"hash supplied but verification not enabled"*), `CONFIG_ARCHIVE`. Plus `RDEPENDS += libgcc` (swupdate threads need libgcc_s.so.1 — without it: *"Broken pipe"*) and `/etc/hwrevision` (via ota-bootmgr — without it: *"Compatible SW not found"*). `swupdate`+`swupdate-www` gated into the image. | — | closed |
| 5 | ~~`.swu` + `sw-description` (dual-slot) + `build-ota.py` packer~~ **DONE + HW-VERIFIED 2026-05-27** — `scripts/build-ota.py` builds per-slot FAT boot images, emits a dual-selection (`stable,slot_a` / `stable,slot_b`) sw-description (raw rootfs+boot writes `installed-directly` + bootenv flip, sha256 per image), packs a CPIO `.swu`. `ota-apply` auto-selects the inactive slot. **Full OTA verified on HW:** `ota-apply` over Ethernet wrote slot B, flipped `BOOT_ORDER=B A`, reboot booted rootfs_b (p4), mark-good committed B. | — | closed |
| 6 | ~~mark-good health-check service~~ **DONE (basic) 2026-05-27** — `ota-mark-good` resets the running slot's counter + prefers it (health = reached multi-user; harden later). | — | basic done |
| 7 | HW test: ~~happy path + rollback + OTA apply~~ **DONE 2026-05-27** (boot A, env seed, mark-good, B→A fall-through, AND full `.swu` apply → slot B → reboot → mark-good, all on HW); **TODO:** power-fail test (yank power mid-write) | 0.25 d | Low |
| **Total** | | **M1 functionally complete + HW-verified** | only the power-fail test + a few hardening items remain |

## What's left (M1)

The end-to-end A/B OTA loop is implemented and HW-verified. Outstanding:

- **Power-fail test** (Task 7 tail) — yank power mid-`ota-apply` and mid-reboot;
  confirm the running slot is untouched and still boots. *(Only functional gap.)*
- **Commit** the swupdate apply fixes (BOOTLOADERHANDLER/HASH_VERIFY/STATIC_LINKED,
  libgcc, hwrevision, `installed-directly`) — uncommitted at time of writing.

**M1 hardening (nice-to-have, not blocking):**
- **Redundant U-Boot env** (`CONFIG_SYS_REDUNDAND_ENVIRONMENT`) — power-fail-during-
  `saveenv` safety; needs a board-header patch (header-driven, not Kconfig).
- **mark-good health check** — currently "reached multi-user"; harden to check key
  services / network.
- **Hardware watchdog** — is the RK3506 WDT wired/usable? Tightens the
  "boots but hangs before mark-good" case.
- **`/data` grow-on-first-boot** — the wks fixes `/data` at 512 MiB; grow to fill
  the eMMC (and fix the GPT backup-header-not-at-disk-end warning, e.g. `sgdisk -e`).
- **`/etc/sw-versions`** — richer installed-version reporting.

**Answered/closed open questions:** U-Boot env (env-in-MMC @12 MiB, verified);
bootcount (script-managed counters, no `CONFIG_BOOTCOUNT`); eMMC size (confirmed
8 GB); `/data` migration (accept clean reflash for the one-time A/B transition).

**Out of scope (M2+):** signed `.swu` (M2), recovery slot (M3), hawkBit/Suricatta
remote (see below), bootloader OTA (M4, never fully brick-safe).

## Remote delivery later

The eventual managed path reuses everything above: enable SWUpdate's **Suricatta**
client (hawkBit backend) in the recipe config, stand up a hawkBit server, and enroll
devices (token/gateway). No change to the partition layout, boot logic, or
`sw-description` — only the transport. So M1's local push and the future remote
rollout are the same framework, configured differently.

## Integration with secure boot / verity

The secure-boot + dm-verity work lives on `core3506-optee-secure-boot`. When OTA and
that branch converge:
- Each rootfs slot carries its **own dm-verity hash**; the signed boot FIT for each
  slot pins its slot's roothash in the kernel cmdline.
- M2 turns on SWUpdate **signed `.swu`** (CMS/RSA) with the public key baked into the
  signed initramfs — authenticity that only holds once the chain to that key is
  signed.
- The `uboot-env` partition becomes a trust-sensitive surface — note for the secure
  design (an attacker who can write env can flip slots; bounded by signed slots +
  verity).

These are M2+/cross-branch concerns, captured here so the M1 layout doesn't paint
us into a corner.
