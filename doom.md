# Can this board run Doom?

Short answer: **yes, comfortably** — Doom (1993) is trivial for the CPU on this board. The work is almost entirely on the *display bringup* side, not on Doom itself.


> **Note:** this document is a feasibility study written *before* the DSI panel
> was brought up. Display and touch now work, and DOOM ships as an opt-in demo
> payload (`LUCKFOX_DOOM=1`) as of v1.3.0. Keep it for the reasoning about what
> the hardware can do; for current status see [DSI_PANEL.md](DSI_PANEL.md) and
> the release notes.

## TL;DR

| Phase | Effort | Risk |
|-------|--------|------|
| 1. Display bringup (panel + VOP/DSI driver + DTS) | 2–5 days | **High** — depends on which panel and how well its init sequence is documented |
| 2. Get Doom rendering on `/dev/fb0` or DRM card0 | 0.5–1 day | Low — well-trodden path |
| 3. Input + audio polish | 1–2 days | Low |
| **Total** | **~1 working week** | Display is the only real unknown |

## What the hardware can do

The Core3506 / RK3506B has plenty of headroom for Doom:

- **CPU**: 3× Cortex-A7 @ 1608 MHz, ARMv7-A with NEON. Doom shipped on a 386DX/33 with 4 MB RAM — a single A7 core at 1.6 GHz is roughly two orders of magnitude faster than the historical baseline. Even the original Pi Zero (1× ARM11 @ 1 GHz) runs Doom fine; this board is well above that.
- **RAM**: 512 MB. Doom + freedoom WAD is < 20 MB resident.
- **Storage**: 8 GB eMMC. The current image is ~250 MB, leaving room for a Doom recipe + WAD with tons to spare.
- **Display path**: the RK3506 has a VOP (Rockchip's display controller) and a MIPI DSI output. The vendor BSP kernel ships an `rk3506-display.config` fragment that enables VOP + MIPI DSI (referenced in `linux-rockchip-rk3506_6.1.bb:92` and `BRIEFING.md:98`). It's just **not enabled in the current build** — display was deliberately deferred (`README.md:66`, roadmap `README.md:177`).
- **No GPU / no VPU**: doesn't matter. Doom is pure software rendering at 320×200, nowhere near a memory-bandwidth bottleneck on this SoC.
- **No HDMI**: the RK3506 has no HDMI controller. The screen has to be MIPI DSI, parallel RGB, or SPI — whatever the carrier wires up.

## What's already in place

- Kernel 6.1 vendor BSP with display drivers (just not configured in)
- `rk3506-display.config` fragment available in-tree
- Display pipeline in `rk3506.dtsi` (VOP, MIPI DSI nodes ready to be referenced and enabled in the board DTS)
- USB host port verified end-to-end → USB keyboard for input is plug-and-play
- glibc + systemd userspace, so any standard SDL2 or framebuffer Doom port slots in cleanly

## What's missing (in order of difficulty)

### 1. Display bringup — the actual hard part

This is the only step with real risk. The work breaks down as:

1. Add the `rk3506-display.config` fragment to `KERNEL_CONFIG_FRAGMENTS` in `meta-rockchip-rk3506/recipes-kernel/linux/linux-rockchip-rk3506_6.1.bb` (one-line change).
2. Extend `meta-rockchip-rk3506/recipes-kernel/linux/files/rk3506b-luckfox-core3506.dts` with the panel node, VOP enable, MIPI DSI / SPI / RGB enable, and any pinmux + backlight + reset GPIOs the panel needs. The `rk3506.dtsi` upstream of this file already declares the controllers — we just need to turn them on and describe the panel.
3. Provide the panel's init sequence. For a vendor-blessed panel (one with a `panel-simple` or `panel-rockchip-*` compatible driver in the BSP), this is essentially boilerplate. For a no-name panel, it's a manual port of the manufacturer's init register dump.

Difficulty depends almost entirely on the panel choice:

| Panel type | Effort | Notes |
|-----------|--------|-------|
| MIPI DSI with vendor support (one of Rockchip's reference panels) | ~1–2 days | Lift DTS from a vendor EVB DTS, patch in the right pinmux |
| SPI panel (ST7789, ILI9341 etc.) via `fbtft` | ~1 day | Very common pattern, lots of prior art, gives you `/dev/fb0` directly |
| Parallel RGB / unknown MIPI panel without a driver | 3–5 days | You'll be debugging panel timings and init sequences with a scope |

Recommendation: **if the screen choice is still open, pick a small SPI panel with `fbtft` support** (ST7789 240×240 / 240×320 is a sweet spot). It gives you `/dev/fb0` with no DRM/KMS plumbing, costs $5, and any fbdev Doom port renders to it in a few hours.

### 2. Doom userspace

Several options, all easy:

| Port | Backend | Yocto recipe | Notes |
|------|---------|--------------|-------|
| `chocolate-doom` | SDL2 (kmsdrm or fbcon) | exists in `meta-openembedded/meta-oe` | Closest to original, supports kbd/joystick |
| `prboom-plus` | SDL2 | `meta-oe` | More features, slightly heavier |
| `fbDoom` | direct `/dev/fb0` | needs a ~20-line custom recipe | No SDL dep, smallest footprint, ideal for a tiny SPI panel |
| `doomgeneric` | swappable backend | custom recipe | Easiest to retarget if the panel needs a custom blit path |

Plus a WAD:
- **`freedoom`** — Apache-2.0-licensed WAD, exists as a recipe in `meta-games` or as a single 4–13 MB tarball install.

Wiring it into the image is just appending to `IMAGE_INSTALL` in `meta-luckfox-distro/recipes-core/images/luckfox-image-minimal.inc`.

### 3. Input

USB host already works (`README.md:170` lists USB1 host as verified). A USB keyboard is plug-and-play with `evdev`. SDL2 picks it up automatically. Optionally, GPIO buttons via `gpio-keys` in the DTS for a handheld-style build.

### 4. Audio (optional — Doom runs muted fine)

The RK3506 has I2S + an internal ACODEC and the vendor BSP supports both, but neither is wired up in our DTS or kernel config. Adding sound is its own ~1–2 day side quest (DTS nodes + ALSA UCM + `alsa-lib` is already in the image via `alsa-utils`). Skip for v1.

## Suggested concrete plan

1. **Pick the panel first.** This dictates everything else. Default recommendation: ST7789 240×240 SPI module (~$5, well-supported by `fbtft`, gives `/dev/fb0` immediately).
2. Add the `rk3506-display.config` fragment (or for SPI/fbtft, just `CONFIG_FB_TFT=m` + `CONFIG_FB_TFT_ST7789V=m` in a new `display.cfg` fragment) to the kernel recipe `SRC_URI`.
3. Extend the board DTS with the panel node + SPI controller enable + reset/backlight GPIOs. Test: `cat /dev/urandom > /dev/fb0` should produce visible noise.
4. Add `chocolate-doom` (or `fbDoom`) and `freedoom` to `IMAGE_INSTALL` in `luckfox-image-minimal.inc`. For SDL2 + fbcon: `SDL_VIDEODRIVER=fbcon chocolate-doom -iwad /usr/share/games/doom/freedoom1.wad`.
5. Plug in a USB keyboard. Done.

Performance expectation: Doom's native cap is 35 fps. At 320×240 on an A7 @ 1.6 GHz, you'll hit it without breaking a sweat — power draw should stay well under the 0.61 W single-core stress figure in the README.

## What it does *not* enable

- Anything that wants a GPU (Quake 3, GLES demos, accelerated browsers) — there is no GPU on this part.
- Video decode at any meaningful resolution — there is no VPU on this part.
- High-resolution UI work — the VOP can drive small panels, but you're not running a desktop on this thing.

Doom is roughly the upper bound of "fun graphical software" on this board, and it's a comfortable upper bound rather than a stretch.

---

## Notes — running Doom on the Luckfox 7" DSI Touchscreen

Confirmed panel (from product page + Luckfox wiki):

| Spec | Value |
|------|-------|
| Panel | 7" IPS, 800×480, 60 Hz |
| Display interface | MIPI DSI (lane count not published; almost certainly 2-lane for this resolution class) |
| Touch controller | FT5x06 (FocalTech, capacitive, 5-point) over I²C |
| Backlight | sysfs PWM, `/sys/class/backlight/*/brightness` (0–255) |
| Cable | 15-pin 1.0 mm FPC, 160 mm |
| Officially supported on | Pi5/CM5/CM4/CM3+/CM3 *and* **Luckfox Lyra (RK3506 — same SoC as our target)** |

This shifts the earlier estimate. Replace the "SPI panel" recommendation with a more honest plan for this specific screen.

### Hardware prerequisite — handled

DSI is exposed on the SoM/lower layer and will be wired out to the panel's 15-pin FPC by hand. This was the previous gating concern; with that resolved the project becomes a pure software exercise. Things to keep in mind during wiring:

- Pi-style 15-pin FPC pinout is the de-facto standard the supplied 160 mm cable expects — matching it lets the cable plug straight in.
- Power: 5 V (backlight LEDs), 3.3 V (logic + touch), GND.
- Signals: DSI clock + data lane(s) (likely 2-lane), I²C SDA/SCL for FT5x06 touch, plus PWM for backlight.
- Recommended: cross-check the wiring against the Luckfox Lyra carrier schematic before powering up — the Lyra is the same SoC and uses this same panel, so its FPC pin assignment is the most reliable reference.

### Display driver work — much easier than first assumed

**Critical finding**: the Luckfox Lyra dev board is also RK3506-based, and Luckfox has **already adapted this exact 7" DSI screen for the Lyra** — alongside their 4.3"/5"/10.1" panels and a list of Waveshare DSI screens. Their wiki ([Luckfox Lyra-Pi / DSI](https://wiki.luckfox.com/Luckfox-Lyra-Pi/DSI/)) describes a `luckfox-config` tool for runtime panel selection plus a manual DTS path in their SDK. This means:

- The MIPI DSI host driver, the panel driver, and the panel timings already exist in a kernel forked from the same `rockchip-linux/kernel develop-6.1` branch we already use.
- The recipe in this repo already declares `COMPATIBLE_MACHINE = "(luckfox-core3506|luckfox-lyra.*)"` (`linux-rockchip-rk3506_6.1.bb:31`), so the Lyra was already in scope.

So instead of forward-porting from the Pi BSP, the realistic plan is:

1. **DSI controller bringup on RK3506** — enable `rk3506-display.config`, light up the VOP + MIPI DSI nodes in our board DTS. Reference: pull from the Luckfox Lyra DTS for the same panel — most of it transfers verbatim.
2. **Panel driver** — likely already in the Luckfox Lyra kernel tree. Action: diff the Lyra branch against our pinned SRCREV, cherry-pick the panel driver + bindings + any DTSI display node tweaks. If the SDK lives at a different SRCREV than ours, we either bump our SRCREV to match or carry the panel patches as a small `SRC_URI` patch series. Risk drops from "port a driver" to "rebase patches" — call it 1–2 days, mostly mechanical.
3. **Touch driver** — FT5x06, well-supported in mainline (`drivers/input/touchscreen/edt-ft5x06.c`), kernel option `CONFIG_TOUCHSCREEN_EDT_FT5X06=m`. DTS node hangs off whichever I²C bus the FPC routes touch to. ~30 lines of DTS.
4. **Backlight** — `pwm-backlight` node pointing at whichever PWM channel feeds the panel BL_EN. Standard.
5. **Pinmux re-targeting** — the Lyra carrier and the Foxbridge carrier route DSI/I²C/PWM to different SoC pins. The driver work is shared; the pinctrl entries in our `rk3506b-luckfox-core3506.dts` need to match Foxbridge's actual routing. This is the only step that can't be copy-pasted from the Lyra DTS.

Revised effort for *this specific panel*: **2–4 days for display bringup** (down from the earlier 3–7 day estimate), assuming the carrier exposes DSI at all (see hardware prerequisite above). The Lyra precedent is the big de-risker.

### Updated total effort

| Step | Days | Notes |
|------|------|-------|
| 1. Hand-wire DSI from lower layer to panel FPC | user-handled | Resolved — DSI exposed on SoM/lower layer |
| 2. RK3506 DSI + panel bringup (cherry-pick from Lyra kernel) | 2–4 | Lyra precedent on same SoC — mostly mechanical |
| 3. FT5x06 touch + backlight DTS | 0.5 | Boilerplate |
| 4. Doom + freedoom recipes into image | 0.5 | `meta-oe` already has it |
| **Software total** | **3–5 days** | |

### Sources

- [Luckfox 7-inch DSI Touchscreen product page](https://www.luckfox.com/EN-7inch-DSI-Touchscreen)
- [Luckfox wiki: 7-DSI-Touchscreen](https://wiki.luckfox.com/Display/7inch-DSI-Touchscreen/)
- [Luckfox wiki: Luckfox Lyra-Pi / DSI](https://wiki.luckfox.com/Luckfox-Lyra-Pi/DSI/) — confirms RK3506 + this exact 7" panel are an officially adapted combination

---

## Investigation log & task list — display bringup DONE (2026-05-25)

**Panel actually used: Luckfox `10.1-DSI-TOUCH-A`** (800×1280 IPS, GT9271 touch,
I2C waveshare backlight) — *not* the 7" FT5x06 panel the section above plans
around. The 7" effort was abandoned earlier; this is the panel that shipped and
is wired to the Foxbridge carrier. **The high-risk Phase 1 (display bringup) is
complete and HW-validated** — the board can now drive a framebuffer, so Doom is
firmly in reach.

### Done ✅
- [x] **DSI display working on HW.** VOP → DSI D-PHY (936 Mbps × 2 lanes) →
      ILI9881C panel; mode `800x1280@60` (70 MHz pixel clock). `modetest -M
      rockchip -s 75:800x1280` draws the SMPTE pattern; confirmed by eye.
- [x] **`/dev/fb0` exists** via DRM fbdev emulation (`CONFIG_DRM_FBDEV_EMULATION`)
      — a plain fbdev Doom port (`fbDoom`/`doomgeneric`) can render directly, no
      DRM/KMS plumbing needed. DRM `card0` also available for KMS ports.
- [x] **Backlight** — `waveshare_bl` (I2C `0x45`), `/sys/class/backlight/`, 0–255.
- [x] **Boot splash** — `psplash` on `/dev/fb0` (default Yocto image), via
      `packagegroup-luckfox-screen`. Custom logo = follow-up (psplash bbappend).
- [x] **Clean non-secure image + autoboot** — secure-boot toggles off in
      `build/conf/local.conf`; fixed `IMAGE_BOOT_FILES` so the screen build's
      `-dsi.dtb` is also deployed under the fixed name extlinux expects
      (`meta-luckfox-bsp/conf/machine/include/luckfox-rk3506.inc`).

### In progress 🔧
- [~] **Touch (GT9271 @ I2C `0x5d`).** Chip detected (`ID 9271`), but mainline
      `goodix` needs an interrupt — without one it fails `-EINVAL (-22)`. The
      panel's "reserved" FPC pins 5/6 are the touch INT/RST, routed by Foxbridge
      to **GPIO1_C5** / **GPIO0_C0** (PCB net trace of `kicad/foxbridge/.../dsi`).
      Current attempt: mainline goodix with `interrupts` + `irq-gpios = GPIO0_C0`
      (no reset; chip self-inits to 0x5d). **If no evdev events → INT is
      GPIO1_C5, swap.** Edits in `…/files/rk3506b-luckfox-core3506-dsi.dts`.
- [~] **Display rotation** — panel is native portrait 800×1280; want 90° CCW
      (landscape). Adding DRM panel `rotation` + console/splash rotation.

### Doom 🎯 — RUNNING on HW (2026-05-25) ✅
- [x] **Doom renders on the panel, landscape.** Used `doomgeneric` with a
      direct-`/dev/fb0` backend (no meta-oe → no chocolate-doom; and the DISTRO
      drops opengl/x11/wayland → SDL is out). Backend patch rotates 90° CCW +
      ×2-scales the 640×400 frame to fill 1280×800 landscape. `freedoom` IWAD
      bundled. Toggle: `LUCKFOX_DOOM = "1"`. Recipes in
      `meta-luckfox-distro/recipes-games/{doomgeneric,freedoom}`; run on-target
      with the `doom` launcher. Orientation confirmed correct by eye.
      (Gotcha fixed: backend crashed when `/dev/input` was absent — patched to
      guard `opendir` and treat "no keyboard" as non-fatal.)
- [x] **Splash landscape** — psplash `--angle 270` via systemd drop-in bbappend
      (`recipes-core/psplash/psplash_%.bbappend`).
- [ ] **Interactive input.** No input device yet → Doom plays the attract-mode
      demo. The fb backend grabs evdev keyboards at startup, so a **USB keyboard**
      (USB host verified) makes it playable immediately — plug in *before*
      launching `doom`. Arrows=move/turn, Ctrl=fire, Space=use, Enter/Esc=menu.
- Touch hardware works (GT9271 poll-mode, valid multitouch on `event0` — see
  [`DSI_PANEL.md`](DSI_PANEL.md)) but is **not** wired to Doom. Doom is keyboard-only
  (USB keyboard); a touch→key control shim was considered and dropped as not worth
  the effort.
- [ ] (optional) Console-on-panel landscape (CONFIG_VT+fbcon); custom splash/Doom
      title logo; audio via I2S+ACODEC.

### Key reference state
- Display DTS/cfg: `meta-rockchip-rk3506/recipes-kernel/linux/files/{rk3506b-luckfox-core3506-dsi.dts,display-dsi.cfg}`
- Splash: `display-dsi.cfg` (FBDEV emulation/logo) + `psplash` in `packagegroup-luckfox-screen.bb`
- Build under a PTY (`script -qfc …`) to dodge the host's py3.12 bitbake fork deadlock.
