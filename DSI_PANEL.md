# 10.1" DSI Touch Panel — Status & Bring-up

Living document for the 7" / 10.1" DSI panel work on the Foxbridge carrier
(LuckFox Core3506 SoM). Consolidates the former `DSI_TOUCH_DEBUG.md` and
`SCREEN-FOLLOW_UP.md`. Last updated **2026-05-26**.

## Status — display + touch WORKING on hardware

Target panel: **[Luckfox 10.1-DSI-TOUCH-A](https://www.luckfox.com/10.1-DSI-TOUCH-A)**
— 800×1280 IPS, 10-point Goodix **GT9271** capacitive touch, I2C backlight MCU
(waveshare-backlight) at 0x45. This is Luckfox's own panel for the Lyra Pi; we
replicate the SDK's known-good config.

| Subsystem | State | Evidence |
|-----------|-------|----------|
| **Display** | ✅ working (2026-05-25) | VOP → DSI (936 Mbps × 2 lanes) → ILI9881C; SMPTE pattern confirmed by eye via `modetest`. Boot logo/psplash on `/dev/fb0`, landscape (`rotation=<270>`). |
| **Backlight** | ✅ working | `waveshare_bl` driver bound at I2C2 0x45 (`UU` in `i2cdetect`); `/sys/class/backlight/` device present. |
| **Touch** | ✅ working (2026-05-26) | GT9271 at I2C2 0x5d; goodix **poll-mode** driver. Valid multitouch on `event0` (`ABS_MT_POSITION_X/Y` in range, clean `BTN_TOUCH`), confirmed on cold boot with no intervention, repeatable across reboots. |

The touch path is poll-mode because the panel FPC exposes **no touch INT line** on
this carrier — see [Touch driver](#touch-driver-poll-mode) below.

## What's in the repo

All gated behind `MACHINE_FEATURES += "screen"` (see `build/conf/local.conf`).

| File | Purpose |
|------|---------|
| `meta-rockchip-rk3506/recipes-kernel/linux/files/rk3506b-luckfox-core3506-dsi.dts` | DSI variant DTS. `simple-panel-dsi` with verbatim Luckfox SDK "10inch1" ILI9881C init sequence, 2-lane RGB888, 800×1280 @ 70 MHz, `rotation=<270>` (landscape). `&i2c2` adds `gt9271@0x5d` (compatible+reg only) and `waveshare_backlight@0x45`. |
| `.../files/display-dsi.cfg` | Kernel config fragment: Rockchip VOP + DW MIPI DSI + simple-panel-dsi + Goodix touch + waveshare backlight + fbdev/console/LOGO (boot splash) + USB-HID. |
| `.../files/0001-add-waveshare-dsi-backlight-driver.patch` | Backports `waveshare-backlight.c` from the Lyra SDK (backlight MCU at 0x45). |
| `.../files/0002-goodix-touch-poll-mode.patch` | Backports the Lyra SDK goodix poll fallback: when `client->irq == 0`, drive the GT9271 with a 17 ms (60 fps) i2c poll timer instead of an interrupt. **Required** — without it the stock driver fails probe (`request_threaded_irq` with irq 0 → `-EINVAL`). |
| `.../linux-rockchip-rk3506_6.1.bb` | Kernel recipe — `SRC_URI:append` for the `screen` feature pulls in the cfg + both patches. |

Driver/config source: the **Luckfox Lyra SDK** (`Luckfox_Lyra_SDK_250815.tar.gz`,
`rk/kernel` @ `696a854` `luckfox-linux-6.1-rk3506`; Lyra touch node in
`arch/arm/boot/dts/rk3506-luckfox-lyra.dtsi`). The same configuration is proven
on Luckfox's own Lyra Ultra board, which is where it was taken from.

## Build & deploy

```sh
# $LUCKFOX_DIR is your clone of this repository; $BUILD_DIR your build tree.
source "$LUCKFOX_DIR/sources/poky/oe-init-build-env" "$BUILD_DIR"
bitbake virtual/kernel        # ~10–15 min on hot sstate
```

Fast iteration without a full reflash — SSH + extlinux DTB/zImage swap (see
[[reference_ssh_extlinux_kernel_swap]] in auto-memory). Default extlinux label is
`luckfox-screen` on screen-test boards. When the network is up the board is at
`foxbridge.local`; when it isn't, use the serial console (see
[DEV_SETUP.md](DEV_SETUP.md)).

## Verify on hardware

```sh
# display
cat /sys/class/drm/card0-DSI-1/status        # connected
modetest -M rockchip -s <conn>@<crtc>:800x1280   # SMPTE colour bars

# backlight + touch present on the bus
i2cdetect -y -r 2                            # 0x45 UU and 0x5d UU
ls /sys/class/backlight/

# touch driver bound, poll-mode (no IRQ line)
dmesg | grep Goodix                          # "ID 9271, version: 1070" + input device
cat /proc/interrupts | grep -i goodix        # NONE = poll path active (expected)
```

### Touch capture (no `evtest` on the image)

Read the raw evdev stream and look for events while touching. **Use a long
window** and touch throughout — a short fixed window invites false "zero" readings
if you don't touch during it (this bit us; see [history](#history)).

```sh
rm -f /tmp/ev.bin
cat /dev/input/event0 > /tmp/ev.bin &       # event node may renumber after a rebind
CPID=$!; sleep 30                            # touch continuously, varied spots
kill $CPID
echo "events=$(( $(wc -c < /tmp/ev.bin) / 16 ))"
hexdump -C /tmp/ev.bin | head               # decode below
```

A working touch shows `EV_ABS` records with `ABS_MT_POSITION_X` (code `0x35`) and
`ABS_MT_POSITION_Y` (`0x36`) in range (X 0–800, Y 0–1280), plus `BTN_TOUCH`
(`0x14a`) and `SYN_REPORT`. Each evdev record is 16 bytes on this 32-bit kernel:
`[ts:8][type:2 LE][code:2 LE][value:4 LE]`.

## Touch driver (poll-mode)

The 10.1-DSI-TOUCH-A FPC carries DSI + I2C + power only — **no touch INT line**
(confirmed from the Lyra schematic and panel pinout; FPC pins 5/6 are panel-enable,
not touch). So the GT9271 is serviced by a 60 fps i2c poll timer
(`0002-goodix-touch-poll-mode.patch`), and the DT node is the Lyra's minimal form:
`compatible = "goodix,gt9271"; reg = <0x5d>;` with no interrupt/reset GPIOs.

Driver can be rebound at runtime (event node may renumber — re-check
`/proc/bus/input/devices`):

```sh
echo 2-005d > /sys/bus/i2c/drivers/Goodix-TS/unbind
echo 2-005d > /sys/bus/i2c/drivers/Goodix-TS/bind
```

### GT9271 I2C quick-reference (raw poking)

- 16-bit register addresses. Status `0x814E`: bit7 (`0x80`) = buffer ready,
  bits[3:0] = touch count.
- Point 0 @ `0x8150`, 8 bytes: `[track_id, Xlo, Xhi, Ylo, Yhi, szLo, szHi, rsvd]`.
- **Ack after read (mandatory):** `i2ctransfer -y 2 w3@0x5d 0x81 0x4e 0x00`
  (`w3`, not `w2` — else the chip never advances past the stale frame).
- Product ID @ `0x8140` (4 bytes ASCII) = `39 32 37 31` = "9271".
- Config @ `0x8047` (read 12 B): `82 21 03 01 05 0a …` → ver / Xmax / Ymax / ntouch.

## Hardware notes (Foxbridge ↔ panel 22-pin FPC)

- **DSI**: 3 MIPI lanes (CLK + D0 + D1); link trains at 936 Mbps × 2.
- **Touch**: pure I2C2 to GT9271 @ 0x5d — no INT pin.
- **Power**: panel 3.3 V is board-sourced on the DSI connector (always-on rail,
  `vcc3v3_lcd` is a phandle target only — Foxbridge has no power MOSFET, unlike the
  Lyra which power-*sequences* the panel). 5 V + GND via a separate 2-wire header.
- **FPC pin 5 → `GPIO1_C4`** (sysfs gpio **52** = gpiochip1 base 32 + line 20),
  10 kΩ pull-up = panel/screen enable, held high. Driving it low does **not** cut
  the touch IC's power (no i2c errors observed) ⇒ pin 5 gates display only.
- **FPC pin 6 → `GPIO0_B7`**, floating (Lyra "swap option", NC).

## Things to watch

| Symptom | Likely cause | Mitigation |
|---------|--------------|------------|
| Modeset commits but panel dark | Init sequence mismatch | Compare against SDK "10inch1" (we use it verbatim). |
| Image corrupt / coloured stripes | DSI signal integrity at 2-lane rate | Try `dsi,lanes = <1>` (lower max framerate). |
| `i2cdetect` misses 0x45 / 0x5d | FPC connector seating / solder | **Reseat the FPC** (see history), inspect pins under magnification. |
| Touch driver doesn't bind / `-22` | Missing poll-mode patch | Ensure `0002-goodix-touch-poll-mode.patch` is applied (no IRQ line on this FPC). |
| Touch "reports nothing" | Capture window missed, **or** marginal FPC seating | Use a long capture window + touch throughout; reseat the FPC. |
| Image upside-down / mirrored | Rotation mismatch | Adjust `rotation` on the panel node. |

## History

**Pre-arrival (2026-05-09):** two stand-in panels were tried and neither worked
end-to-end — a Waveshare 7" (ICN6211 bridge, strapped for I2C-config the FPC
doesn't expose) and an original Pi 7" Touchscreen (Atmel v2 firmware the legacy
driver doesn't handle). Both ruled out as panel-side issues; the Foxbridge DSI host
+ I2C were proven good. Settling on the native-FPC 10.1-DSI-TOUCH-A removed both
classes of problem.

**Touch "no coordinates" (2026-05-25 → resolved 2026-05-26):** on first bring-up the
GT9271 read its ID and scanned but reported zero coordinates. A thorough elimination
(clean I2C, chip alive + correctly configured, power present, two boards, new cable)
pointed at the panel-internal touch path, and the panel was A/B-tested on a Lyra
(where touch worked). On re-test the fault **did not reproduce** — touch worked with
valid coordinates, cold boot, no intervention. Two factors explain the earlier
result: (1) the **FPC was reseated** when the panel came back from the Lyra, most
likely clearing a marginal connection (not a panel defect); and (2) some early
"zero" captures were **false negatives from a too-short capture window** — the chip
was fine, the window just missed the touch. Lessons baked into the [verify](#touch-capture-no-evtest-on-the-image)
steps above: long window, touch throughout, report time-to-first-event; don't trust
a single short-window zero. (Also confirmed: Doom does **not** `EVIOCGRAB` `event0`
— multitouch reads fine with `doom.service` active.)

## References

- [Luckfox 10.1-DSI-TOUCH-A product page](https://www.luckfox.com/10.1-DSI-TOUCH-A)
- [Lyra DSI wiki](https://wiki.luckfox.com/Luckfox-Lyra/Buildroot/Lyra-pinout/DSI/)
- Foxbridge DSI schematic and design notes — see the companion hardware
  repository, [Rufilla/LuckFox-Core3506-Carrier](https://github.com/Rufilla/LuckFox-Core3506-Carrier)
- [`doom.md`](doom.md) — Doom on the DSI framebuffer (touch→key shim is the remaining TODO)
