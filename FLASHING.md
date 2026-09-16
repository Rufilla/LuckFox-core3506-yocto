# Flashing the LuckFox Core3506 / Foxbridge

This guide covers two flows:

1. **[Flash a prebuilt release image](#flash-a-prebuilt-release-image)** — recommended for end users.
2. **[Flash an image you built yourself](#flash-an-image-you-built-yourself)** — for developers iterating on this Yocto layer.

Both flows use `rkdeveloptool` over USB while the SoC is in **maskrom mode**. There is no JTAG, no SD-card boot path on Core3506-0808 (the on-module eMMC is mandatory; see [DEV_SETUP.md](DEV_SETUP.md)), and no recovery partition — if a flash goes wrong, you re-enter maskrom and reflash.

---

## Prerequisites

### Hardware

- LuckFox Core3506-0808 SoM on a Foxbridge Rev A carrier (or compatible).
- USB-C cable from the host PC to the **OTG0** port of the Foxbridge (this is the maskrom-capable port; OTG1/H6 is for USB host use).
- A way to hold the **BOOT** signal low while applying power or reset. On Foxbridge this is the BOOT test pad on the carrier; in lab automation it's typically a relay.
- 5 V power for the carrier (the same USB-C used for maskrom does NOT power the board — the carrier is externally powered).

### Software (host PC)

- `rkdeveloptool` — the canonical Rockchip utility packaged in most Linux distros uses the verbose verbs this guide is written against (`list`, `boot`, `write`, `reset`). Older builds / forks expose the short verbs (`ld`, `db`, `wl`, `rd`) — they map 1:1; substitute accordingly.
- USB permissions: either run as root, or install a udev rule allowing your user to access USB device `2207:350f` (RK3506 maskrom).

```bash
# Verify rkdeveloptool is installed and what variant you have
rkdeveloptool --help 2>&1 | head -20
```

If you see `list / boot / write / reset` your tool uses the names in this guide. If you see `ld / db / wl / rd` you have an older variant — they map 1:1.

---

## Entering maskrom mode

The RK3506 enters maskrom mode if `BOOT` is held low at the moment a reset/power-on happens. The exact procedure depends on your carrier:

**Foxbridge Rev A (manual):**
1. Short the **BOOT** test pad to ground.
2. Press and release the **RESET** button (still shorting BOOT).
3. Wait ~1 s, then release BOOT.
4. Confirm the device appeared:
   ```bash
   $ rkdeveloptool list
   DevNo=1  Vid=0x2207,Pid=0x350f,LocationID=...  Maskrom
   ```

**Foxbridge Rev A (automating maskrom entry):**
If you are reflashing often, a two-channel USB relay across the BOOT and RESET
pads lets you script maskrom entry: BOOT on → RESET on → wait 0.3 s → RESET off
→ wait 1.5 s → BOOT off → check `rkdeveloptool list`. Any relay board with a
CLI will do; Rufilla's own rig tooling is not published.

If `rkdeveloptool list` reports **"No devices in rockusb mode found"**, the board did not enter maskrom — power-cycle and try again, making sure BOOT was held *before* reset was released.

---

## Flash a prebuilt release image

Each tagged release ships three files:

| File | Purpose |
|---|---|
| `luckfox-image-minimal-foxbridge-vX.Y.Z.wic` | Full eMMC image (~406 MiB for v1.2.0) |
| `rk3506_spl_loader_vX.Y.Z.bin` | Combined USB download loader (DDR init + USB plug + SPL) |
| `SHA256SUMS` | Integrity check for both files |

Prebuilt images are published as **GitHub release assets**:
[github.com/Rufilla/LuckFox-core3506-yocto/releases](https://github.com/Rufilla/LuckFox-core3506-yocto/releases).
The WIC image is attached to each release (it is too large to keep in the git
tree); the loader binary and `SHA256SUMS` are also in `release/<version>/` in
this repository.

### Steps

```bash
# 0. Verify checksums
sha256sum -c SHA256SUMS

# 1. Put the board in maskrom mode (see section above), then confirm
rkdeveloptool list
# expect: DevNo=1  Vid=0x2207,Pid=0x350f,...  Maskrom

# 2. Download the loader into SRAM. This wakes up DDR and starts the
#    USB-mass-storage-style download protocol.
rkdeveloptool boot rk3506_spl_loader_vX.Y.Z.bin
# expect: Downloading bootloader succeeded.

# 3. Write the WIC starting at LBA 0. ~400 MiB typically completes in well
#    under a minute over USB2 HS (~37 s observed on v1.2.0); slower hubs or
#    cables can stretch this to a few minutes.
rkdeveloptool write 0 luckfox-image-minimal-foxbridge-vX.Y.Z.wic
# expect: Write LBA from file (100%)

# 4. Reboot out of maskrom into the freshly-flashed image
rkdeveloptool reset
```

If you watch the serial console (FT2232 channel B, 115200 8N1 once the kernel is up) you will see U-Boot, OP-TEE, then the kernel boot, then a `foxbridge login:` prompt. Default credentials: user `root`, no password (`debug-tweaks` is enabled in v1.x — change this for production use).

### After first boot

The image lands with **WiFi disabled by default**. To bring it up:

```bash
# Edit the WiFi credentials
vi /etc/wpa_supplicant/wpa_supplicant-wlan.conf
# Replace CHANGEME_SSID and CHANGEME_PSK with your network details.

# Restart the auto-bringup unit. It reads the file, refuses to run if the
# CHANGEME marker is still present, otherwise starts wpa_supplicant -Dwext
# on the first wl* interface and runs udhcpc.
systemctl restart foxbridge-wifi

# Verify
journalctl -u foxbridge-wifi --no-pager
ip -4 addr show
```

The status page at `http://foxbridge.local/` shows both Ethernet and WiFi state.

---

## Flash an image you built yourself

If you're hacking on the Yocto layers and want to flash your own build:

```bash
# 1. Build the image (Yocto cache will skip most work on rebuilds).
#    To build the 7" DSI screen variant instead of the headless image,
#    add `MACHINE_FEATURES:append = " screen"` to conf/local.conf — that
#    swaps KERNEL_DEVICETREE to the DSI DTB and pulls libdrm-tests
#    (modetest et al.) into the rootfs. Same flash sequence either way.
source sources/poky/oe-init-build-env build
bitbake luckfox-image-minimal

# 2. Build the combined USB download loader. The raw DDR blob alone is NOT
#    accepted by rkdeveloptool — you must run boot_merger to wrap DDR + SPL +
#    usbplug into the format the maskrom expects.
RKBIN=build/tmp-glibc/work/armv7at2hf-neon-oe-linux-gnueabi/u-boot-rockchip-rk3506/2017.09/rkbin
( cd $RKBIN && ./tools/boot_merger RKBOOT/RK3506BMINIALL.ini )
# Output: $RKBIN/rk3506_spl_loader_v1.06.111.bin

# 3. Maskrom + flash + reset (see prebuilt-image section above)
rkdeveloptool list
rkdeveloptool boot $RKBIN/rk3506_spl_loader_v1.06.111.bin
rkdeveloptool write 0 build/tmp-glibc/deploy/images/luckfox-core3506/luckfox-image-minimal-luckfox-core3506.rootfs.wic
rkdeveloptool reset
```

**Common pitfalls** (each cost real time during bringup — listing them so you skip the hour I lost):

- **`Opening loader failed, exiting download boot!`** — you passed the raw DDR blob to `boot`. Use `boot_merger` to produce the combined loader.
- **`bitbake -c cleansstate linux-rockchip-rk3506` does NOT clean U-Boot.** A fresh kernel build with sstate-cached U-Boot can mismatch versions of patches you applied to U-Boot. If U-Boot needs to pick up a change, also run `bitbake -c cleansstate u-boot-rockchip-rk3506`.
- **Console silent after flash?** First check the baud — the kernel uses 115200 (`console=ttyFIQ0,115200` on the cmdline) but a sstate-cached U-Boot may still emit at 1500000 until the kernel takes over the UART. If you see garbage early then proper text after a few seconds, that's expected; if you see nothing at all, your TTY may have re-numbered (FTDI rebinds shuffle `/dev/ttyUSBN` numbers — identify the right port via `udevadm info -q property /dev/ttyUSBx | grep INTERFACE_NUM=01`).
- **FT2232 disconnects when the relay pulses RESET.** On lab rigs where the FT2232 console adapter shares the carrier's power/ground reference, pulsing the RESET relay briefly drops the FT2232 off USB; any `cat`/`screen`/`picocom` already open on `/dev/ttyUSB*` silently EOFs and you see zero output even though the board is booting normally. Open the console **after** the relay sequence settles (~1 s), and send a CR to elicit the login prompt if the boot banner has already scrolled past.
- **Board enters maskrom but `boot` still fails** — the host's USB hub may have reset the device between `list` and `boot`. Re-list immediately before `boot`; if the device is gone, repeat the BOOT/RESET dance.
- **Signed FIT: silent hang at boot after a partial rebuild.** With `RK3506_TEE_FIT_SIGNATURE = "1"` the SPL embeds the FIT-signing pubkey and refuses any FIT whose config signature doesn't verify. The recipe builds the SPL and the signed FIT together in one `do_compile`, so a *full* build is always self-consistent — but if only part of the chain rebuilds, the SPL pubkey and the FIT signature can desync and the SPL rejects the FIT with **no console output** (looks like a dead board). Fix: `bitbake -c cleansstate u-boot-rockchip-rk3506 && bitbake luckfox-image-minimal` so the SPL and FIT regenerate together.

---

## Verifying OP-TEE (source-built secure image)

Builds with `OPTEE_PROVIDER = "optee-os-rk3506"` replace the vendor TEE blob
with the from-source OP-TEE port and — with `RK3506_TEE_FIT_SIGNATURE = "1"` —
ship an RSA-2048-signed U-Boot FIT that the SPL enforces (un-fused). After
flashing, confirm the secure world is live from the serial console:

```bash
# 1. The OP-TEE driver bound and exposed the TEE devices:
ls -l /dev/tee*            # expect /dev/tee0 and /dev/teepriv0
dmesg | grep -i optee      # expect "optee: probing for conduit method"
                           #  + "optee: initialized driver"

# 2. tee-supplicant is running (shipped by optee-client):
systemctl status tee-supplicant

# 3. Run the test suite (shipped by optee-test) — TEST_PLAN.md §7.1 subset:
xtest _1001 _1004 _1006    # quick smoke test
xtest                      # full run (regression + benchmark)
# expect the tail to read "<n> subtests of which 0 failed"

# 4. SMP: all three Cortex-A7 cores up via OP-TEE PSCI:
nproc                      # expect 3
```

To prove signature *enforcement* (optional, and it bricks that flash until you
reflash a good image): re-sign the FIT with a different key and confirm the SPL
refuses to boot it. A correctly-signed FIT boots straight through.

---

## Recovering a bricked board

There is no way to brick a Core3506 short of physical damage to the eMMC. The maskrom ROM is hardware; it always activates if BOOT is held low at reset. So:

1. Short BOOT test point to ground, reset, remove BOOT short.
2. `rkdeveloptool list` should show maskrom.
3. Reflash a known-good image.

If `rkdeveloptool list` does not show the device, the problem is host-side or USB-cable-side — try a different cable, a different port, and verify `lsusb` shows `2207:350f` while in maskrom.
