SUMMARY = "Minimal dm-verity activator + initramfs init for Core3506 secure boot"
DESCRIPTION = "Self-contained dm-verity device activator (DM ioctls, no \
cryptsetup) plus the initramfs /init that opens the verified root from a baked-in \
authenticated verity.conf and switch_root's into it — avoids the long quoted \
dm-mod.create kernel cmdline."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://dm-verity-open.c \
    file://init \
"

S = "${WORKDIR}"

# Tiny static-ish C tool; uses only libc + linux/dm-ioctl.h.
do_compile() {
    ${CC} ${CFLAGS} ${LDFLAGS} -O2 -Wall -o dm-verity-open ${WORKDIR}/dm-verity-open.c
}

do_install() {
    install -d ${D}${sbindir}
    install -m 0755 dm-verity-open ${D}${sbindir}/dm-verity-open
    # The initramfs /init lives at the image root (kernel runs /init).
    install -d ${D}/
    install -m 0755 ${WORKDIR}/init ${D}/init
}

FILES:${PN} = "${sbindir}/dm-verity-open /init"
# /init in the package root is intentional (initramfs entry point).
INSANE_SKIP:${PN} = "ldflags"
