SUMMARY = "OP-TEE Trusted OS (secure world) for Rockchip RK3506B"
DESCRIPTION = "Source-built OP-TEE for the RK3506B (Luckfox Core3506 / Lyra). \
Produces the secure-world tee.bin that the U-Boot recipe packs into the boot \
FIT when OPTEE_PROVIDER = \"optee-os-rk3506\". The RK3506 plat-rockchip flavor \
is upstream in OP-TEE/optee_os master (accepted 2026-06; RFC issue #7820)."
HOMEPAGE = "https://www.op-tee.org/"

LICENSE = "BSD-2-Clause"
LIC_FILES_CHKSUM = "file://LICENSE;md5=c1f21c4f72f372ef38a5a4aee55ec173"

# No release tag carries the rk3506 flavor yet: it landed on master AFTER
# 4.10.0 was cut (conf.mk @ tag 4.10.0 has no rk3506 block; master does).
# So pin a master SHA, not a tag. This tip includes the port commit
# a9c67eb4 ("plat-rockchip: add support for RK3506") plus the later rk3506
# CFG_8250_UART_FLUSH_TIMEOUT fix (merged 2026-07-08) — needed because RK3506
# shares UART0 between OP-TEE and Linux ttyS0. Re-pin to the first release
# tag >= 4.11.0 once rk3506 ships in a release.
# The RK3506 platform port is upstream in OP-TEE master; see the commits above
# for provenance rather than any downstream document.
SRC_URI = "git://github.com/OP-TEE/optee_os.git;protocol=https;branch=master"
SRCREV = "298746f9e2907886dcf68a0586b9fb0671ad7043"

S = "${WORKDIR}/git"
B = "${WORKDIR}/build"

# Core3506 + Lyra RK3506 Luckfox boards.
COMPATIBLE_MACHINE = "luckfox-core3506"

inherit deploy python3native

# gen_tee_bin.py (ELF -> tee.bin) needs pyelftools; core embeds the TA
# verification pubkey via python3-cryptography.
DEPENDS = "python3-pyelftools-native python3-cryptography-native"

# RK3506B is a tri-core Cortex-A7 (ARMv7-A, 32-bit) — OP-TEE builds arm32.
OPTEEMACHINE = "rockchip-rk3506"

# R1 HW secure-RAM isolation. Single source of truth with the U-Boot recipe:
# both read RK3506_TEE_HW_ISOLATE ("0"/"1"). "1" builds OP-TEE with TEE_RAM
# low + FW_DDR slot-0 protect and CFG_TZDRAM_START=0x1000 (matches the FIT
# load the U-Boot recipe picks); "0" leaves TEE_RAM in the mid-DRAM hole at
# 0x18000000. Not yet validated on Core3506 — keep no-isolate until a clean boot.
RK3506_TEE_HW_ISOLATE ??= "0"

# Bringup/validation verbosity. The G7 PASS baseline built at log level 2;
# lower to "1" for a final release once a clean boot is proven.
OPTEE_CFG_LOG_LEVEL ??= "2"

EXTRA_OEMAKE = " \
    PLATFORM=${OPTEEMACHINE} \
    CFG_ARM32_core=y \
    CFG_TEE_CORE_LOG_LEVEL=${OPTEE_CFG_LOG_LEVEL} \
    CFG_RK3506_TEE_HW_ISOLATE=${@'y' if d.getVar('RK3506_TEE_HW_ISOLATE') == '1' else 'n'} \
    CROSS_COMPILE_core=${HOST_PREFIX} \
    HOST_PREFIX=${HOST_PREFIX} \
    COMPILER=gcc \
    LIBGCC_LOCATE_CFLAGS='${HOST_CC_ARCH}${TOOLCHAIN_OPTIONS}' \
    NOWERROR=1 \
    V=1 \
    O=${B} \
"

# Keep Yocto's exported host/target flags from overriding OP-TEE's own
# bare-metal toolchain selection (mirrors meta-arm's optee-os recipe).
LDFLAGS[unexport] = "1"
CPPFLAGS[unexport] = "1"
AS[unexport] = "1"
LD[unexport] = "1"

CFLAGS += "--sysroot=${STAGING_DIR_HOST}"

# python3-cryptography needs the legacy provider for the TA pubkey step.
export OPENSSL_MODULES = "${STAGING_LIBDIR_NATIVE}/ossl-modules"

do_compile:prepend() {
	PLAT_LIBGCC_PATH=$(${CC} -print-libgcc-file-name)
}

do_compile() {
    # core/tee.bin only — we ship no TAs from this recipe (the U-Boot FIT
    # just needs the secure-world image). Matches the validated bringup
    # build (out/core/tee.bin), avoiding the example-TA build.
    oe_runmake -C ${S} ${B}/core/tee.bin
}
do_compile[cleandirs] = "${B}"

do_install() {
    # OP-TEE's rockchip tee.bin carries a 28-byte OPTEE-v1 header; the
    # U-Boot FIT wants the raw payload, so drop the first 28 bytes. The
    # U-Boot recipe's do_compile copies from exactly this path
    # (${RECIPE_SYSROOT}${datadir}/optee-rk3506/tee.bin). datadir is in the
    # default SYSROOT_DIRS, so it stages into u-boot's recipe sysroot.
    install -d ${D}${datadir}/optee-rk3506
    tail -c +29 ${B}/core/tee.bin > ${D}${datadir}/optee-rk3506/tee.bin
    chmod 0644 ${D}${datadir}/optee-rk3506/tee.bin
}

# tee.bin is built for one SoC — make it machine-specific.
PACKAGE_ARCH = "${MACHINE_ARCH}"

# Ship the staged blob (path is fixed, not ${BPN}, because the U-Boot recipe
# reads exactly ${datadir}/optee-rk3506/tee.bin), else do_package QA fails
# "installed but not shipped".
FILES:${PN} += "${datadir}/optee-rk3506"

# Raw (unstripped) image + ELF for bench debugging only; not packaged.
do_deploy() {
    install -d ${DEPLOYDIR}/optee-rk3506
    install -m 0644 ${B}/core/tee.bin ${DEPLOYDIR}/optee-rk3506/tee-with-header.bin
    install -m 0644 ${B}/core/tee.elf ${DEPLOYDIR}/optee-rk3506/tee.elf
}
addtask deploy before do_build after do_install

# Raw firmware blob in datadir; standard ELF QA doesn't apply.
INHIBIT_PACKAGE_STRIP = "1"
INSANE_SKIP:${PN} = "textrel buildpaths"
