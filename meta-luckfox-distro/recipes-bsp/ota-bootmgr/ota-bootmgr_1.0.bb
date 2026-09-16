SUMMARY = "A/B OTA boot manager — env seeding + slot mark-good (M1)"
DESCRIPTION = "Userspace half of the A/B boot scheme (see OTA_SWUPDATE_PLAN.md): \
ota-env-init seeds the persistent U-Boot env with the A/B selector bootcmd + \
slot try-counters on first boot; ota-mark-good commits the running slot as \
healthy on a successful boot so U-Boot keeps choosing it (and rolls back \
otherwise). The selector itself is the ab_select env script (bootcmd=run \
ab_select), seeded by ota-env-init from /etc/ota-bootenv. ota-apply drives \
SWUpdate to write the inactive slot from a .swu."
HOMEPAGE = "https://github.com/Rufilla/LuckFox-core3506-yocto"

LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://ota-bootenv \
    file://ota-env-init \
    file://ota-env-init.service \
    file://ota-mark-good \
    file://ota-mark-good.service \
    file://ota-apply \
    file://hwrevision \
"

S = "${WORKDIR}"

FILES:${PN} += "${systemd_system_unitdir}"

# fw_setenv/fw_printenv (env seeding + mark-good); swupdate (ota-apply).
RDEPENDS:${PN} = "libubootenv-bin swupdate"

COMPATIBLE_MACHINE = "luckfox-core3506"

inherit systemd

SYSTEMD_SERVICE:${PN} = "ota-env-init.service ota-mark-good.service"

do_install() {
    install -d ${D}${sysconfdir}
    install -m 0644 ${WORKDIR}/ota-bootenv ${D}${sysconfdir}/ota-bootenv
    # /etc/hwrevision: SWUpdate's default CONFIG_HW_COMPATIBILITY_FILE. The
    # revision (2nd field) is matched against the .swu sw-description's
    # hardware-compatibility list; without it swupdate rejects with
    # "Compatible SW not found". Must stay in sync with build-ota.py.
    install -m 0644 ${WORKDIR}/hwrevision ${D}${sysconfdir}/hwrevision

    install -d ${D}${sbindir}
    install -m 0755 ${WORKDIR}/ota-env-init ${D}${sbindir}/ota-env-init
    install -m 0755 ${WORKDIR}/ota-mark-good ${D}${sbindir}/ota-mark-good
    install -m 0755 ${WORKDIR}/ota-apply ${D}${sbindir}/ota-apply

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${WORKDIR}/ota-env-init.service ${D}${systemd_system_unitdir}/
    install -m 0644 ${WORKDIR}/ota-mark-good.service ${D}${systemd_system_unitdir}/
}
