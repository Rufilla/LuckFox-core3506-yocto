SUMMARY = "Doom (doomgeneric) — direct-framebuffer port for the 10.1-DSI-TOUCH-A"
DESCRIPTION = "doomgeneric built against its Linux VT/framebuffer backend, \
patched to rotate 90° CCW and 2x-scale the 640x400 Doom frame so it fills the \
portrait 800x1280 DSI panel as a 1280x800 landscape image. Software-rendered \
to /dev/fb0 (no GPU/SDL/X needed). Keyboard input via evdev (USB host). Run \
with the bundled `doom` launcher (pulls in the freedoom IWAD)."
HOMEPAGE = "https://github.com/ozkl/doomgeneric"
LICENSE = "GPL-2.0-only"
LIC_FILES_CHKSUM = "file://LICENSE;md5=b234ee4d69f5fce4486a80fdaf4a4263"

SRC_URI = "git://github.com/ozkl/doomgeneric.git;protocol=https;branch=master \
           file://0001-fbdev-rotate-scale.patch \
           file://doom \
           file://doom.service"
SRCREV = "dcb7a8dbc7a16ce3dda29382ac9aae9d77d21284"

S = "${WORKDIR}/git"

# freedoom supplies the IWAD the launcher points at.
RDEPENDS:${PN} = "freedoom"

# Autostart Doom on boot. The service ships in this package, so it's only
# present (and enabled) when Doom is compiled in (LUCKFOX_DOOM = "1").
inherit systemd
SYSTEMD_SERVICE:${PN} = "doom.service"
SYSTEMD_AUTO_ENABLE = "enable"

# Makefile.linuxvt hardcodes CC=clang and appends its own -DNORMALUNIX/-DLINUX
# via CFLAGS+=, so override only CC on the command line (leaving CFLAGS to come
# from the environment + the Makefile's appends).
do_compile() {
    oe_runmake -C ${S}/doomgeneric -f Makefile.linuxvt CC="${CC}"
}

do_install() {
    install -d ${D}${bindir}
    install -m0755 ${S}/doomgeneric/doomgeneric ${D}${bindir}/doomgeneric
    install -m0755 ${WORKDIR}/doom ${D}${bindir}/doom

    install -d ${D}${systemd_system_unitdir}
    install -m0644 ${WORKDIR}/doom.service ${D}${systemd_system_unitdir}/doom.service
}

# Doom is for /dev/fb0; no GL/X deps.
INSANE_SKIP:${PN} += "already-stripped"
