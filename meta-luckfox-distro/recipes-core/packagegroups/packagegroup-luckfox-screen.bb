SUMMARY = "LuckFox screen-bringup userspace tools"
DESCRIPTION = "Pulled in only when MACHINE_FEATURES contains 'screen'. \
Provides command-line utilities for verifying the DSI panel + KMS \
pipeline. libdrm-tests bundles every binary from poky's libdrm \
recipe — modetest, proptest, vbltest, drmdevice — sufficient for \
first-light: 'modetest -M rockchip -s <conn>:800x480 -F SMPTE' draws \
a colour-bars test pattern. \
\
kmscube is intentionally omitted: it lives in poky's recipes-graphics \
but requires DISTRO_FEATURES contains 'opengl', which our luckfox \
distro doesn't enable (the RK3506 has no GPU; pulling Mesa swrast \
just for a triangle demo costs ~10s of MB for marginal benefit). \
If you want kmscube anyway, add 'opengl' to DISTRO_FEATURES in \
local.conf and append ' kmscube' to this packagegroup's RDEPENDS."
LICENSE = "MIT"

inherit packagegroup

# libdrm-tests is a subpackage of poky's libdrm recipe (FILES:${PN}-tests
# = ${bindir}/*) — picks up every binary the recipe builds. No separate
# libdrm-tools subpackage exists in scarthgap.
# psplash draws a graphical boot splash to /dev/fb0 (provided by the kernel's
# DRM fbdev emulation — see display-dsi.cfg). Its systemd units start the
# splash early and quit it once userspace is up. Default Poky logo for now;
# swap in a branded image later via a psplash bbappend + generated header.
RDEPENDS:${PN} = " \
    libdrm-tests \
    psplash \
"
