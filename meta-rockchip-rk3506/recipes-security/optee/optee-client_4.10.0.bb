# OP-TEE normal-world client: libteec + tee-supplicant.
#
# M5 (2026-05-16). Custom pinned recipe (owner decision: minimal
# layer surface over meta-arm). Pinned to upstream tag 4.10.0 to
# match our in-tree optee_os 4.10.0 (94b09fb) — libteec/tee-supplicant
# share the OPTEE_SMC/RPC ABI version with the secure world.
#
# Built via the upstream top-level Makefile (well-trodden cross
# build; avoids the cmake-toolchain dance). Artefacts land in
# ${B}/export/usr — staged into ${D}. A systemd unit is shipped by
# us (upstream 4.10.0 does not include one); this distro is systemd.

SUMMARY = "OP-TEE client library (libteec) and tee-supplicant"
DESCRIPTION = "Normal-world userspace for OP-TEE: the libteec client \
library and the tee-supplicant RPC daemon. Pinned to 4.10.0 to match \
the in-tree optee_os secure world."
HOMEPAGE = "https://github.com/OP-TEE/optee_client"
LICENSE = "BSD-2-Clause"
LIC_FILES_CHKSUM = "file://LICENSE;md5=69663ab153298557a59c67a60a743e5b"

SRC_URI = "\
    git://github.com/OP-TEE/optee_client.git;protocol=https;branch=master \
    file://tee-supplicant.service \
"
SRCREV = "9f5e90918093c1d1cd264d8149081b64ab7ba672"

S = "${WORKDIR}/git"

inherit systemd

# WITH_TEEACL=0: libteeacl is an optional access-control helper that
# pulls libuuid + a $(CROSS_COMPILE)pkg-config the OE env doesn't
# provide. xtest + tee-supplicant don't use it — dropping it removes
# the dependency entirely (cleaner than wiring OE pkg-config in).
EXTRA_OEMAKE = "\
    CROSS_COMPILE=${TARGET_PREFIX} \
    O=${B}/out \
    WITH_TEEACL=0 \
    CFG_TEE_SUPP_LOG_LEVEL=1 \
"

do_compile() {
    oe_runmake -C ${S} ${EXTRA_OEMAKE}
}

do_install() {
    # Upstream installs a clean tree under out/export/usr.
    install -d ${D}${sbindir} ${D}${libdir} ${D}${includedir}
    cp -a ${B}/out/export/usr/sbin/tee-supplicant ${D}${sbindir}/
    cp -a ${B}/out/export/usr/lib/libteec.so* ${D}${libdir}/
    cp -a ${B}/out/export/usr/include/* ${D}${includedir}/

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${WORKDIR}/tee-supplicant.service \
        ${D}${systemd_system_unitdir}/
}

SYSTEMD_SERVICE:${PN} = "tee-supplicant.service"
SYSTEMD_AUTO_ENABLE = "enable"

FILES:${PN} += "${systemd_system_unitdir}/tee-supplicant.service"
# libteec.so is a versioned runtime lib; keep the SONAME symlink in -dev
FILES:${PN} += "${libdir}/libteec.so.*"
FILES:${PN}-dev += "${libdir}/libteec.so ${includedir}"

COMPATIBLE_MACHINE = "luckfox-core3506"
