# EXPERIMENTAL — RK3506 rkbin blob recipe
# Stages proprietary Rockchip boot blobs for image assembly.
# These blobs are REQUIRED for DDR initialization — the board cannot boot without them.

SUMMARY = "Rockchip binary blobs for RK3506 boot chain"
DESCRIPTION = "Proprietary DDR init, SPL, and OP-TEE binaries from Rockchip's rkbin \
repository. Required by the RK3506 boot chain: BootROM loads MiniLoaderAll which \
initializes DDR using the closed-source DDR init blob before any open-source code executes."
HOMEPAGE = "https://github.com/rockchip-linux/rkbin"

LICENSE = "CLOSED"
LICENSE_FLAGS = "commercial"

SRC_URI = "git://github.com/rockchip-linux/rkbin.git;protocol=https;branch=master"
SRCREV = "74213af1e952c4683d2e35952507133b61394862"

S = "${WORKDIR}/git"

COMPATIBLE_MACHINE = "luckfox-core3506"

inherit deploy

# RK3506 blobs (for RK3506G2-based boards: Lyra, Lyra B, Lyra Plus)
RKBIN_DDR_RK3506 = "bin/rk35/rk3506_ddr_750MHz_v1.06.bin"
RKBIN_SPL = "bin/rk35/rk3506_spl_v1.11.bin"
RKBIN_USBPLUG = "bin/rk35/rk3506_usbplug_v1.03.bin"
RKBIN_TEE = "bin/rk35/rk3506_tee_v2.10.bin"

# RK3506B blobs (for RK3506B-based boards: Core3506, Lyra Ultra)
RKBIN_DDR_RK3506B = "bin/rk35/rk3506b_ddr_750MHz_v1.06.bin"

# Select DDR blob based on SoC sub-variant
# RK3506B boards set RKBIN_SOC_VARIANT = "rk3506b" in machine config
RKBIN_SOC_VARIANT ?= "rk3506"
RKBIN_DDR = "${@d.getVar('RKBIN_DDR_RK3506B') if d.getVar('RKBIN_SOC_VARIANT') == 'rk3506b' else d.getVar('RKBIN_DDR_RK3506')}"

# MINIALL.ini selection
RKBIN_MINIALL_INI = "${@'RKBOOT/RK3506BMINIALL.ini' if d.getVar('RKBIN_SOC_VARIANT') == 'rk3506b' else 'RKBOOT/RK3506MINIALL.ini'}"

do_configure[noexec] = "1"
do_compile[noexec] = "1"

do_install() {
    # Verify MINIALL.ini exists at pinned commit
    if [ ! -f "${S}/${RKBIN_MINIALL_INI}" ]; then
        bbfatal "MINIALL.ini not found at ${RKBIN_MINIALL_INI} — rkbin commit may not support RK3506"
    fi

    # Verify all required blobs exist
    for blob in "${RKBIN_DDR}" "${RKBIN_SPL}" "${RKBIN_USBPLUG}" "${RKBIN_TEE}"; do
        if [ ! -f "${S}/${blob}" ]; then
            bbfatal "Required blob not found: ${blob} at rkbin commit ${SRCREV}"
        fi
    done

    install -d ${D}${datadir}/rkbin
    install -m 0644 ${S}/${RKBIN_DDR} ${D}${datadir}/rkbin/
    install -m 0644 ${S}/${RKBIN_SPL} ${D}${datadir}/rkbin/
    install -m 0644 ${S}/${RKBIN_USBPLUG} ${D}${datadir}/rkbin/
    install -m 0644 ${S}/${RKBIN_TEE} ${D}${datadir}/rkbin/
    install -m 0644 ${S}/${RKBIN_MINIALL_INI} ${D}${datadir}/rkbin/
}

do_deploy() {
    install -d ${DEPLOYDIR}
    install -m 0644 ${S}/${RKBIN_DDR} ${DEPLOYDIR}/
    install -m 0644 ${S}/${RKBIN_SPL} ${DEPLOYDIR}/
    install -m 0644 ${S}/${RKBIN_USBPLUG} ${DEPLOYDIR}/
    install -m 0644 ${S}/${RKBIN_TEE} ${DEPLOYDIR}/
    install -m 0644 ${S}/${RKBIN_MINIALL_INI} ${DEPLOYDIR}/
}

addtask deploy after do_install

FILES:${PN} = "${datadir}/rkbin"

# These are pre-built binaries, not built from source
INSANE_SKIP:${PN} = "already-stripped"
