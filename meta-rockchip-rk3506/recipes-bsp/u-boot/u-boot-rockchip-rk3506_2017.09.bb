# RK3506 U-Boot recipe (Rockchip vendor fork, 2017.09)
# Produces idbloader.img (DDR init + U-Boot SPL) and u-boot.img (U-Boot + OP-TEE FIT).
# Validated on Core3506-0808 + foxbridge carrier (2026-04-10).

SUMMARY = "Rockchip U-Boot for RK3506"
DESCRIPTION = "Rockchip's vendor U-Boot 2017.09 fork for the RK3506 SoC family. \
Produces idbloader.img (DDR init + U-Boot SPL) and u-boot.img (FIT with OP-TEE + U-Boot) \
for the Rockchip boot chain."
HOMEPAGE = "https://github.com/rockchip-linux/u-boot"

LICENSE = "GPL-2.0-only"
LIC_FILES_CHKSUM = "file://Licenses/gpl-2.0.txt;md5=b234ee4d69f5fce4486a80fdaf4a4263"

DEPENDS += "bc-native dtc-native python3-native"

PROVIDES += "virtual/bootloader"

# Match the upstream Das U-Boot NVD product so cve-check produces a populated
# report. Default would be BPN ("u-boot-rockchip-rk3506") which matches nothing.
CVE_PRODUCT = "u-boot"

# U-Boot source (Rockchip vendor fork)
#
# Console: NATIVE UART0 (0xff0a0000) on the GPIO0_C6/C7 console pads. We do NOT
# apply any console-retarget patch — the pristine rk3506 U-Boot already has
# stdout-path=&uart0, CONFIG_DEBUG_UART_BASE=0xff0a0000 and an empty
# board_debug_uart_init ("No need to change uart": UART0 is the reset-default pad
# function). This matches the optee_os port's W14 UART0 default and the kernel
# (console=ttyS0) — one consistent UART0 chain, upstream-aligned (serial0/UART0).
# 2026-06-01: dropped the former 0006 patch that crossbarred the pads to UART1
# via RMIO (a leftover from the original Lyra-derived UART1 OP-TEE console).
# 0003 (M2/M3 memory map) IS required for the from-source OP-TEE; 0004 (R1 atags
# reloc) is applied only when RK3506_TEE_HW_ISOLATE = "1".
SRC_URI = " \
    git://github.com/rockchip-linux/u-boot.git;protocol=https;branch=next-dev;name=default \
    git://github.com/rockchip-linux/rkbin.git;protocol=https;branch=master;name=rkbin;destsuffix=rkbin \
    file://rk3506-distroboot.config \
    file://rk3506-env-mmc.config \
    file://fit-signature.config \
"

# 0001 reorders RKIMG_BOOTCOMMAND to "run distro_bootcmd; boot_fit;" so an
# extlinux/zImage boot partition boots (the unsigned/Phase-2 path). For the
# Phase 3b-B SIGNED-kernel build we must NOT apply it: the pristine
# CONFIG_FIT_SIGNATURE branch is "boot_fit;"-only, so only the VERIFYING boot_fit
# runs — there is no distro/extlinux step that could load an unverified zImage
# and silently bypass the signature check. (CONFIG_FIT_SIGNATURE is already on in
# Phase 2 for the signed u-boot.itb, so the boot order can't be gated on that
# macro — it's gated here on RK3506_KERNEL_FIT_SIGNATURE instead.)
SRC_URI += "${@'' if d.getVar('RK3506_KERNEL_FIT_SIGNATURE') == '1' else 'file://0001-rk3506-add-distro-boot-fallback.patch'}"

SRCREV = "b14196eade471bbc000c368f8555f2a2a1ecc17d"
SRCREV_rkbin = "74213af1e952c4683d2e35952507133b61394862"

SRCREV_FORMAT = "default_rkbin"

S = "${WORKDIR}/git"
# In-tree build — vendor U-Boot 2017.09 doesn't support out-of-tree well
B = "${S}"

COMPATIBLE_MACHINE = "luckfox-core3506"

# Machine config sets UBOOT_MACHINE to the defconfig target
# For RK3506B boards (Core3506, Lyra Ultra): "rk3506_defconfig"
# The rk3506b.config fragment is applied separately for RK3506B
UBOOT_MACHINE ?= "rk3506_defconfig"

# RK3506B boards need the config fragment overlay
# Machine config sets RK3506_UBOOT_CONFIG_FRAGMENT = "rk3506b.config" for RK3506B
RK3506_UBOOT_CONFIG_FRAGMENT ?= ""

# rkbin blob paths
RKBIN_SOC_VARIANT ?= "rk3506"
RKBIN_DDR = "${@'bin/rk35/rk3506b_ddr_750MHz_v1.06.bin' if d.getVar('RKBIN_SOC_VARIANT') == 'rk3506b' else 'bin/rk35/rk3506_ddr_750MHz_v1.06.bin'}"
RKBIN_TEE = "bin/rk35/rk3506_tee_v2.10.bin"
RKBIN_MINIALL_INI = "${@'RKBOOT/RK3506BMINIALL.ini' if d.getVar('RKBIN_SOC_VARIANT') == 'rk3506b' else 'RKBOOT/RK3506MINIALL.ini'}"

# OP-TEE secure-world provider toggle.
#   "rkbin"           - vendor closed blob rk3506_tee_v2.10.bin (default;
#                       byte-identical to the historical product image).
#   "optee-os-rk3506" - the from-source OP-TEE port (recipe optee-os-rk3506),
#                       the CRA-auditable, source-built replacement.
# The from-source TEE uses the M2/M3-relocated memory map (patch 0003) and
# runs from the M5-relocated TZDRAM 0x18000000; the vendor blob uses the
# stock map and loads at 0x1000. So 0003 is applied ONLY for the source
# provider — flipping back to "rkbin" restores the stock vendor build.
OPTEE_PROVIDER ??= "optee-os-rk3506"
SRC_URI += "${@'file://0003-rk3506-M2-M3-memory-map-recipe-track-buildtree-edits.patch' if d.getVar('OPTEE_PROVIDER') == 'optee-os-rk3506' else ''}"
DEPENDS += "${@'optee-os-rk3506' if d.getVar('OPTEE_PROVIDER') == 'optee-os-rk3506' else ''}"

# R1 HW secure-RAM isolation toggle (default "0"; Phase 2). When "1": OP-TEE
# TEE_RAM is relocated low and FW_DDR slot-0 HW-isolates [0,16M) from the NS
# CPU. Requires OPTEE_PROVIDER = "optee-os-rk3506" (its recipe builds with
# CFG_RK3506_TEE_HW_ISOLATE=y). The 0004 patch relocates the NS boot
# structures above the protected region + migrates the preloader atags out
# of it. NOTE: not yet validated on Core3506 hardware — leave "0" until the
# source-OP-TEE image is proven booting on the board.
RK3506_TEE_HW_ISOLATE ??= "0"
SRC_URI += "${@bb.utils.contains('RK3506_TEE_HW_ISOLATE', '1', 'file://0004-rk3506-R1-relocate-atags-for-hw-isolated-tee.patch', '', d)}"

# FIT optee load: 0x1000 for the vendor blob (RK3506TOS.ini) AND for the
# HW-isolate low-TEE config; 0x18000000 for the source build's M5-relocated
# (no-isolate) TZDRAM.
OPTEE_TEE_LOAD = "${@'0x1000' if (d.getVar('RK3506_TEE_HW_ISOLATE') == '1' or d.getVar('OPTEE_PROVIDER') != 'optee-os-rk3506') else '0x18000000'}"

# Secure-boot / FIT-signature toggle (default "0"; Phase 3). When "1" the SPL
# is built with CONFIG_SPL_FIT_SIGNATURE=y and the FIT is RSA-2048-signed with
# the pubkey embedded in the SPL control DTB, so the SPL refuses an unsigned /
# wrong-key FIT. Enforced UN-FUSED (no OTP/eFuse burn).
#
# RK3506_TEE_FIT_KEY_DIR: dir holding dev.key (RSA-2048 private PEM) + dev.crt
# (matching the FIT signature node's key-name-hint="dev"). Leave empty for a
# dev/eval build and the recipe generates an EPHEMERAL throwaway key under
# ${B}/fit-keys. Production MUST set this to an offline/HSM key dir.
RK3506_TEE_FIT_SIGNATURE ??= "0"
RK3506_TEE_FIT_KEY_DIR ??= ""
# Phase 3b-B: when "1", (a) inject the kernel-FIT verification pubkey into
# u-boot.dtb (see do_compile) so U-Boot's boot_fit can verify the signed kernel,
# and (b) assemble + sign the Rockchip-native boot.img FIT (kernel + DTB +
# resource) here in do_compile, deploy it in do_deploy. Implies
# RK3506_TEE_FIT_SIGNATURE=1 + a real RK3506_TEE_FIT_KEY_DIR. The boot.img build
# consumes the kernel's deployed zImage + DTB, so it depends on the kernel
# do_deploy. (The earlier OE-fitImage attempt's boot.cmd/0007 generic-bootm
# patch are gone — boot_fit verifies the resource FIT natively.)
RK3506_KERNEL_FIT_SIGNATURE ??= "0"
do_compile[depends] += "${@'virtual/kernel:do_deploy' if d.getVar('RK3506_KERNEL_FIT_SIGNATURE') == '1' else ''}"
# CONFIG_FIT_SIGNATURE makes the host mkimage link libcrypto; keygen also
# needs openssl. Pull openssl-native only when signing is enabled.
DEPENDS += "${@'openssl-native' if d.getVar('RK3506_TEE_FIT_SIGNATURE') == '1' else ''}"

inherit deploy

# Host compiler and cross-compile settings for U-Boot build.
# Vendor U-Boot 2017.09 uses HOSTCC=cc by default, which doesn't exist
# in Yocto's build environment. Must explicitly set HOSTCC=gcc.
# KCFLAGS: Disable -Werror — vendor 2017.09 code triggers
# -Wmay-be-uninitialized with GCC 13.x (scarthgap toolchain).
EXTRA_OEMAKE = " \
    HOSTCC='gcc' \
    HOSTCXX='g++' \
    CROSS_COMPILE=${TARGET_PREFIX} \
    PYTHON=python3 \
    KCFLAGS='-Wno-error' \
"

do_configure() {
    # Apply base defconfig (in-tree build)
    oe_runmake ${UBOOT_MACHINE}

    # Apply RK3506B config fragment if specified
    if [ -n "${RK3506_UBOOT_CONFIG_FRAGMENT}" ]; then
        if [ -f "${S}/configs/${RK3506_UBOOT_CONFIG_FRAGMENT}" ]; then
            ${S}/scripts/kconfig/merge_config.sh -m -O ${B} ${B}/.config \
                ${S}/configs/${RK3506_UBOOT_CONFIG_FRAGMENT}
        else
            bbwarn "Config fragment ${RK3506_UBOOT_CONFIG_FRAGMENT} not found, skipping"
        fi
    fi

    # Apply distro boot config fragment (enables CMD_PART, CMD_FS_GENERIC, etc.)
    if [ -f "${WORKDIR}/rk3506-distroboot.config" ]; then
        ${S}/scripts/kconfig/merge_config.sh -m -O ${B} ${B}/.config \
            ${WORKDIR}/rk3506-distroboot.config
    fi

    # Apply persistent-env-in-MMC fragment (OTA A/B foundation — see
    # OTA_SWUPDATE_PLAN.md). Switches env from ENV_IS_NOWHERE to ENV_IS_IN_MMC
    # at a fixed eMMC offset so saveenv persists and Linux fw_setenv can share it.
    if [ -f "${WORKDIR}/rk3506-env-mmc.config" ]; then
        ${S}/scripts/kconfig/merge_config.sh -m -O ${B} ${B}/.config \
            ${WORKDIR}/rk3506-env-mmc.config
    fi

    # FIT-signature: CONFIG_FIT_SIGNATURE + CONFIG_SPL_FIT_SIGNATURE.
    if [ "${RK3506_TEE_FIT_SIGNATURE}" = "1" ] && [ -f "${WORKDIR}/fit-signature.config" ]; then
        ${S}/scripts/kconfig/merge_config.sh -m -O ${B} ${B}/.config \
            ${WORKDIR}/fit-signature.config
        # merge_config.sh -m only merges; enabling FIT_SIGNATURE reveals NEW
        # symbols (e.g. FIT_ROLLBACK_PROTECT) with no default, which would
        # make do_compile's silentoldconfig prompt and abort. Resolve all
        # new symbols to their defaults non-interactively.
        oe_runmake olddefconfig
    fi
}

do_compile:prepend() {
    # Fix SD card boot: arch_cpu_init() unconditionally calls board_set_iomux()
    # which overwrites the BootROM's pin mux with eMMC-specific values.
    # This prevents the SPL from reading u-boot.img when booting from SD card.
    # Fix: remove both the iomux SET (in arch_cpu_init) and UNSET (in
    # spl_board_storages_fixup). The BootROM already configures the correct
    # pin muxing for whichever boot media it selected.
    sed -i 's|board_set_iomux(IF_TYPE_MMC|// board_set_iomux(IF_TYPE_MMC|' \
        ${S}/arch/arm/mach-rockchip/rk3506/rk3506.c
    sed -i '/spl_board_storages_fixup/,/^}/ {
        s|if (loader->boot_device.*|/* SD boot fix: skip iomux unset */|
        s|.*board_unset_iomux.*|/* board_unset_iomux removed */|
    }' ${S}/arch/arm/mach-rockchip/rk3506/rk3506.c
}

do_compile() {
    # make_fit_optee.sh expects tee.bin in the source tree. OPTEE_PROVIDER
    # selects which secure world: the vendor closed blob (default, reverts
    # cleanly) or our from-source port staged by optee-os-rk3506 (already
    # 28-byte-header-stripped, FIT-ready).
    if [ "${OPTEE_PROVIDER}" = "optee-os-rk3506" ]; then
        cp ${RECIPE_SYSROOT}${datadir}/optee-rk3506/tee.bin ${S}/tee.bin
    else
        cp ${WORKDIR}/rkbin/${RKBIN_TEE} ${S}/tee.bin
    fi

    # Build U-Boot (compiles U-Boot + SPL + generates FIT artifacts)
    oe_runmake

    # Phase 3b-B: build + sign the Rockchip-native boot.img FIT (kernel + DTB +
    # resource) AND inject its verification pubkey into u-boot.dtb, in ONE in-tree
    # mkimage call. This MUST happen here — before make_fit_optee.sh packs
    # u-boot.dtb as the u-boot.itb 'fdt' node (the runtime control FDT), after
    # which it is frozen.
    #
    # Why the in-tree mkimage (not the native one): U-Boot proper verifies the
    # boot.img signature with the Rockchip HARDWARE crypto (CONFIG_FIT_HW_CRYPTO +
    # CONFIG_ROCKCHIP_RSA), whose rsa-verify reads the Montgomery constants
    # rsa,np + rsa,c from the key node. The in-tree mkimage -K emits those (same
    # as the SPL key node, which verifies fine); the native mkimage emits
    # rsa,n0-inverse (software path) instead, so HW verify fails with
    # "Verification failed ... 'conf' config node". boot_fit reads the kernel DTB
    # from the FIT /images/resource node (verify=true) and hang()s on a bad
    # signature. -E -p 0x1200 makes it external-data type (fit_is_ext_type).
    # SAME RK3506_TEE_FIT_KEY_DIR/dev key as the u-boot.itb + SPL signing -> one
    # trust anchor BootROM..SPL..U-Boot..kernel.
    if [ "${RK3506_KERNEL_FIT_SIGNATURE}" = "1" ]; then
        KEYDIR="${RK3506_TEE_FIT_KEY_DIR}"
        if [ -z "${KEYDIR}" ] || [ ! -f "${KEYDIR}/dev.key" ]; then
            bbfatal "RK3506_KERNEL_FIT_SIGNATURE=1 requires RK3506_TEE_FIT_KEY_DIR/dev.key (the shared FIT signing key)."
        fi
        # KERNEL_DEVICETREE may list several DTBs; the first is the one that boots.
        KDTB="${@((d.getVar('KERNEL_DEVICETREE') or '').split() or [''])[0]}"
        # Stage the artifacts make_fit_boot.sh expects under ${B}/images.
        rm -rf ${B}/images
        install -d ${B}/images
        install -m 0644 ${DEPLOY_DIR_IMAGE}/zImage  ${B}/images/kernel
        install -m 0644 ${DEPLOY_DIR_IMAGE}/${KDTB}  ${B}/images/dtb
        : > ${B}/images/ramdisk
        # Pack the DTB into a Rockchip 'resource' image as "rk-kernel.dtb" — the
        # exact name rockchip_read_resource_dtb (DEFAULT_DTB_FILE) looks up at
        # boot. resource_tool only auto-renames a packed file to rk-kernel.dtb if
        # its name ends in ".dtb"; a file named plain "dtb" gets stored under its
        # literal (here absolute) path, so the runtime DTB lookup fails (-ENODEV)
        # and boot falls back to the FIT 'fdt' node (placeholder load 0xffffff00)
        # -> data abort. So pack a file literally named rk-kernel.dtb.
        cp ${B}/images/dtb ${B}/images/rk-kernel.dtb
        ${B}/tools/resource_tool --pack --image=${B}/images/second ${B}/images/rk-kernel.dtb
        # Emit the ITS and sign it; -K injects the pubkey (with rsa,np/rsa,c) into
        # u-boot.dtb. Run from ${B}: fit_args.sh sets srctree=$PWD and the ITS
        # /incbin/ paths are relative (./images/...).
        cd ${B}
        ${S}/arch/arm/mach-rockchip/make_fit_boot.sh > ${B}/boot.img.its
        # make_fit_boot.sh emits PLACEHOLDER load/entry addrs (fdt 0xffffff00,
        # kernel 0xffffff01, ramdisk 0xffffff02) that U-Boot fixes up at runtime
        # — but ONLY for UNSIGNED boot: fix_image_set_addr() bails out when the
        # config signature is required ("do not fix if verified-boot"). So a
        # signed boot.img must carry REAL addresses or boot_get_fdt copies the
        # (resource) DTB to 0xffffff00 -> data abort. Substitute the RK3506 env
        # addresses (rk3506_common.h ENV_MEM_LAYOUT_SETTINGS: kernel_addr_r,
        # fdt_addr_r, ramdisk_addr_r), all within the 512 MB DRAM and clear of
        # the HW-isolated TEE region and the loaded FIT.
        sed -i -e 's/0xffffff00/0x0d000000/g' \
               -e 's/0xffffff01/0x08000000/g' \
               -e 's/0xffffff02/0x0e000000/g' ${B}/boot.img.its
        ${B}/tools/mkimage -f ${B}/boot.img.its -k "${KEYDIR}" -K ${B}/u-boot.dtb \
            -E -p 0x1200 -r ${B}/boot.img
        bbnote "Phase 3b-B: built signed boot.img + injected pubkey 'dev' (HW-crypto np/c) into u-boot.dtb"
    fi

    # Build u-boot.itb: the FIT image with OP-TEE + U-Boot.
    # The Makefile's default u-boot.img uses "mkimage -f auto" which omits OP-TEE.
    # The u-boot.itb target uses make_fit_optee.sh which includes the optee node.
    # The Makefile's default TEE offset (0x08400000) is wrong for RK3506 — the
    # load/entry must be OPTEE_TEE_LOAD (0x1000 for the vendor blob per
    # RK3506TOS.ini; 0x18000000 for the source build's M5-relocated TZDRAM).
    # Generate the ITS manually with the correct TEE address.
    ${S}/arch/arm/mach-rockchip/make_fit_optee.sh -t ${OPTEE_TEE_LOAD} > ${B}/u-boot.its

    if [ "${RK3506_TEE_FIT_SIGNATURE}" = "1" ]; then
        # Sign the FIT and embed the RSA pubkey in the SPL control DTB so the
        # SPL enforces the signature (un-fused). make_fit_optee.sh already
        # emits the signature node (key-name-hint="dev"); mkimage signs it.
        KEYDIR="${RK3506_TEE_FIT_KEY_DIR}"
        if [ -z "${KEYDIR}" ]; then
            KEYDIR="${B}/fit-keys"
            if [ ! -f "${KEYDIR}/dev.key" ]; then
                mkdir -p "${KEYDIR}"
                openssl genpkey -algorithm RSA -out "${KEYDIR}/dev.key" -pkeyopt rsa_keygen_bits:2048
                openssl req -batch -new -x509 -key "${KEYDIR}/dev.key" -out "${KEYDIR}/dev.crt" \
                    -days 3650 -subj "/CN=rk3506-optee-dev-fit-signing-key"
                bbwarn "RK3506_TEE_FIT_SIGNATURE: generated EPHEMERAL dev signing key at ${KEYDIR}. Set RK3506_TEE_FIT_KEY_DIR to an offline/HSM key for production."
            fi
        fi
        # Phase 3b: when a real key dir is configured, `make` already FIT-signed
        # its u-boot.img and left a /signature/key-dev node in spl/u-boot-spl.dtb.
        # mkimage's -K below then fails ("Can't add hashes to FIT blob: -5") trying
        # to re-add an existing key-dev. Strip any pre-existing /signature from the
        # SPL control DTB so -K re-adds it cleanly. (No-op when the dtb has none,
        # e.g. the ephemeral-key path; dtc round-trip is content-preserving.)
        if [ "${RK3506_KERNEL_FIT_SIGNATURE}" = "1" ]; then
            ${B}/scripts/dtc/dtc -I dtb -O dts ${B}/spl/u-boot-spl.dtb 2>/dev/null \
              | awk '/^\tsignature \{/{skip=1; next} skip && /^\t\};/{skip=0; next} !skip{print}' \
              | ${B}/scripts/dtc/dtc -I dts -O dtb -o ${B}/spl/u-boot-spl.dtb 2>/dev/null
        fi
        # -p 0x1200 (rockchip OFFS_DATA) is REQUIRED for external-data signing;
        # -K injects the pubkey into the SPL DTB; -r marks the config signature
        # required.
        ${B}/tools/mkimage -f ${B}/u-boot.its -k "${KEYDIR}" -K ${B}/spl/u-boot-spl.dtb \
            -E -p 0x1200 -r ${B}/u-boot.itb
        # Repack u-boot-spl.bin so the SPL carries the now-signed control DTB
        # (mirrors the build's nodtb + [bss-pad] + dtb cat; the pad.bin is
        # absent iff CONFIG_SPL_SEPARATE_BSS). boot_merger below picks this up.
        if [ -f "${B}/spl/u-boot-spl-pad.bin" ]; then
            cat ${B}/spl/u-boot-spl-nodtb.bin ${B}/spl/u-boot-spl-pad.bin ${B}/spl/u-boot-spl.dtb > ${B}/spl/u-boot-spl.bin
        else
            cat ${B}/spl/u-boot-spl-nodtb.bin ${B}/spl/u-boot-spl.dtb > ${B}/spl/u-boot-spl.bin
        fi
    else
        ${B}/tools/mkimage -f ${B}/u-boot.its -E ${B}/u-boot.itb
    fi
    cp ${B}/u-boot.itb ${B}/u-boot.img

    # Build idbloader using boot_merger with the compiled U-Boot SPL.
    # The vendor SPL blob does not support loading OP-TEE from FIT images.
    # The compiled U-Boot SPL (with CONFIG_SPL_OPTEE=y) has the proper FIT
    # parser that loads OP-TEE as firmware and U-Boot as loadable.
    # boot_merger produces the format the BootROM expects (not mkimage -T rksd).
    # Run from rkbin dir so relative paths in the INI resolve correctly.
    cp ${WORKDIR}/rkbin/${RKBIN_MINIALL_INI} ${WORKDIR}/rkbin/miniall-spl.ini
    sed -i "s|FlashBoot=.*|FlashBoot=${B}/spl/u-boot-spl.bin|" ${WORKDIR}/rkbin/miniall-spl.ini
    sed -i "s|^PATH=.*|PATH=${B}/idbloader-spl.bin|" ${WORKDIR}/rkbin/miniall-spl.ini
    sed -i "s|^IDB_PATH=.*|IDB_PATH=${B}/idbloader.img|" ${WORKDIR}/rkbin/miniall-spl.ini
    cd ${WORKDIR}/rkbin && ./tools/boot_merger miniall-spl.ini && cd ${B}

    # Generate the complete built-in default environment (OTA A/B foundation —
    # see OTA_SWUPDATE_PLAN.md). The Makefile's `u-boot-initial-env` target dumps
    # the .rodata.default_environment section from the freshly-built u-boot.bin via
    # objcopy, so it captures the FULL compiled-in env (bootdelay, distro_bootcmd,
    # bootcmd, ...). On a freshly-flashed device the eMMC env region is blank, so
    # fw_setenv MUST seed from this file (-f /etc/u-boot-initial-env) — seeding a
    # hand-written stub instead would REPLACE U-Boot's default env and drop the
    # board to the => prompt instead of autobooting. Ship the real thing.
    oe_runmake u-boot-initial-env
}

# Ship the generated default env to the rootfs as /etc/u-boot-initial-env, in a
# dedicated split package so it only lands when explicitly pulled into the image
# (the OTA gate in luckfox-image-minimal.inc), independent of the empty main
# bootloader package. This is the seed fw_setenv needs on a blank eMMC env region.
do_install() {
    install -d ${D}${sysconfdir}
    install -m 0644 ${B}/u-boot-initial-env ${D}${sysconfdir}/u-boot-initial-env
}

PACKAGES =+ "${PN}-env"
FILES:${PN}-env = "${sysconfdir}/u-boot-initial-env"
CONFFILES:${PN}-env = "${sysconfdir}/u-boot-initial-env"

do_deploy() {
    install -d ${DEPLOYDIR}

    if [ -f "${B}/idbloader.img" ]; then
        install -m 0644 ${B}/idbloader.img ${DEPLOYDIR}/idbloader.img
    fi

    if [ -f "${B}/u-boot.img" ]; then
        install -m 0644 ${B}/u-boot.img ${DEPLOYDIR}/u-boot.img
    fi

    # Phase 3b-B: deploy the signed Rockchip boot.img FIT. The fit .wks writes it
    # raw into a GPT partition named "boot" (rawcopy), where boot_fit scans it
    # (part_get_info_by_name "boot") and verifies its signature before boot.
    if [ "${RK3506_KERNEL_FIT_SIGNATURE}" = "1" ] && [ -f "${B}/boot.img" ]; then
        install -m 0644 ${B}/boot.img ${DEPLOYDIR}/boot.img
    fi

    # Phase 4: stage the in-tree FIT tools so the IMAGE recipe can rebuild + sign
    # boot.img with the dm-verity root hash baked into the kernel DTB bootargs
    # (this resolves the root-hash <-> signed-DTB ordering problem: the hash is
    # only known after the rootfs is built, so the image recipe re-signs).
    # u-boot still builds its own boot.img above (static bootargs + the
    # pubkey injection into u-boot.dtb/u-boot.itb); the image side overwrites just
    # boot.img with the roothash variant when RK3506_ROOTFS_VERITY=1.
    if [ "${RK3506_KERNEL_FIT_SIGNATURE}" = "1" ]; then
        install -d ${DEPLOYDIR}/rk3506-fit-tools/arch/arm/mach-rockchip
        install -m 0755 ${B}/tools/mkimage        ${DEPLOYDIR}/rk3506-fit-tools/mkimage
        install -m 0755 ${B}/tools/resource_tool  ${DEPLOYDIR}/rk3506-fit-tools/resource_tool
        install -m 0755 ${B}/arch/arm/mach-rockchip/make_fit_boot.sh ${DEPLOYDIR}/rk3506-fit-tools/arch/arm/mach-rockchip/
        install -m 0755 ${B}/arch/arm/mach-rockchip/fit_args.sh      ${DEPLOYDIR}/rk3506-fit-tools/arch/arm/mach-rockchip/
        install -m 0644 ${B}/.config ${DEPLOYDIR}/rk3506-fit-tools/.config
    fi

    # Deploy loader binary for rkdeveloptool db (maskrom flashing)
    if [ -f "${B}/idbloader-spl.bin" ]; then
        install -m 0644 ${B}/idbloader-spl.bin ${DEPLOYDIR}/idbloader-spl.bin
    fi

    # Also deploy the raw binaries for debugging
    if [ -f "${B}/u-boot-dtb.bin" ]; then
        install -m 0644 ${B}/u-boot-dtb.bin ${DEPLOYDIR}/u-boot-dtb.bin
    fi
    if [ -f "${B}/spl/u-boot-spl.bin" ]; then
        install -m 0644 ${B}/spl/u-boot-spl.bin ${DEPLOYDIR}/u-boot-spl.bin
    fi
}

addtask deploy after do_compile

# Vendor U-Boot 2017.09 does not follow modern U-Boot conventions.
# Disable QA checks that don't apply.
INSANE_SKIP:${PN} = "ldflags"
