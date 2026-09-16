# Minimal LuckFox image for eMMC boot.
# Flash via rkdeveloptool in maskrom mode.

require luckfox-image-minimal.inc

# wic layout — precedence (secure-boot paths and OTA A/B are mutually exclusive
# for now; combining verity + A/B is a future milestone):
#   verity on             -> GPT, ro verity rootfs + verity-hash + rw data (Phase 4)
#   signed kernel (3b-B)  -> GPT, raw signed boot.img FIT + rw rootfs
#   LUCKFOX_OTA           -> 6-partition A/B GPT (see OTA_SWUPDATE_PLAN.md)
#   else                  -> msdos single-slot zImage+DTB+extlinux
WKS_FILE = "${@'luckfox-rk3506-emmc-verity.wks' if d.getVar('RK3506_ROOTFS_VERITY') == '1' else ('luckfox-rk3506-emmc-fit.wks' if d.getVar('RK3506_KERNEL_FIT_SIGNATURE') == '1' else bb.utils.contains('LUCKFOX_OTA', '1', 'luckfox-rk3506-emmc-ab.wks', 'luckfox-rk3506-emmc.wks', d))}"

# Phase 4: signed/verified rootfs (dm-verity). Default off; "1" implies the
# signed-kernel boot (3b-B) is on, since the verity root hash is authenticated
# via the signed boot.img DTB bootargs.
RK3506_ROOTFS_VERITY ??= "0"
inherit ${@'dm-verity-img' if d.getVar('RK3506_ROOTFS_VERITY') == '1' else ''}

# dm-verity exposes /dev/dm-0 with a 4096-byte logical block (= the verity data
# block size). ext4 fs blocks must be >= the device's logical block, but
# mkfs.ext4 defaults a ~230 MB fs to 1024-byte blocks -> "EXT4-fs: bad block
# size 1024" through verity. Force 4096-byte ext4 blocks for the verity build.
EXTRA_IMAGECMD:ext4:append = "${@' -b 4096' if d.getVar('RK3506_ROOTFS_VERITY') == '1' else ''}"

# A dm-verity rootfs is read-only; OE puts /var, /tmp on tmpfs (volatile) and a
# rw 'data' partition (mmcblk0p4) is mounted at /data for app persistence.
IMAGE_FEATURES:append = "${@' read-only-rootfs' if d.getVar('RK3506_ROOTFS_VERITY') == '1' else ''}"
# The ext4 rootfs must be a fixed size (verity hashes exact content; rawcopied,
# not expanded). Keep it snug but with headroom for the installed packages.
IMAGE_ROOTFS_SIZE:append = "${@' ' if d.getVar('RK3506_ROOTFS_VERITY') != '1' else ''}"
IMAGE_OVERHEAD_FACTOR = "${@'1.3' if d.getVar('RK3506_ROOTFS_VERITY') == '1' else '1.2'}"

# Mount the writable data partition at /data (created empty by the verity .wks).
verity_data_fstab () {
    if ! grep -q "[[:space:]]/data[[:space:]]" ${IMAGE_ROOTFS}/etc/fstab; then
        echo "/dev/mmcblk0p4  /data  ext4  defaults,noatime  0  2" >> ${IMAGE_ROOTFS}/etc/fstab
    fi
    install -d -m 0755 ${IMAGE_ROOTFS}/data
}
ROOTFS_POSTPROCESS_COMMAND:append = "${@' verity_data_fstab;' if d.getVar('RK3506_ROOTFS_VERITY') == '1' else ''}"

# --- Phase 4: verity via signed initramfs (option 2) ------------------------
# Activation is NOT via a long quoted dm-mod.create on the cmdline (U-Boot mangles
# that — see plan §Phase 4); instead the signed FIT ramdisk node carries the
# core3506-verity-initramfs cpio, into which this step bakes the per-image
# authenticated /etc/verity.conf (root hash etc.). The initramfs /init opens the
# verity device (dm-verity-open, DM ioctls) and switch_root's. The kernel cmdline
# is left UNCHANGED (short → console works). Runs as a do_image_wic prefunc after
# do_image_ext4 (verity ext4 + .verity.env exist), rebuilding+signing
# boot-verity.img with the in-tree FIT tools u-boot staged. Pubkey is already in
# the deployed u-boot.itb, so no -K here.
DEPENDS:append = "${@' dtc-native virtual/kernel virtual/bootloader' if d.getVar('RK3506_ROOTFS_VERITY') == '1' else ''}"
do_image_wic[depends] += "${@'virtual/kernel:do_deploy virtual/bootloader:do_deploy core3506-verity-initramfs:do_image_complete' if d.getVar('RK3506_ROOTFS_VERITY') == '1' else ''}"
# Order do_image_wic AFTER do_image_ext4 so the verity ext4 + .verity.env
# (dm_verity_hash postfunc) exist in IMGDEPLOYDIR before rk3506_verity_bootimg.
IMAGE_TYPEDEP:wic:append = "${@' ext4' if d.getVar('RK3506_ROOTFS_VERITY') == '1' else ''}"
# Host tools used by the step.
do_image_wic[depends] += "${@' cpio-native:do_populate_sysroot gzip-native:do_populate_sysroot' if d.getVar('RK3506_ROOTFS_VERITY') == '1' else ''}"

KDTB_FIRST = "${@((d.getVar('KERNEL_DEVICETREE') or '').split() or [''])[0]}"
VERITY_INITRAMFS = "core3506-verity-initramfs-${MACHINE}.cpio.gz"

rk3506_verity_bootimg () {
    # Verity ext4/hash/.env are in IMGDEPLOYDIR at wic time; u-boot/kernel deploy
    # artifacts (FIT tools, zImage, dtb, the base initramfs cpio) are in
    # DEPLOY_DIR_IMAGE. boot-verity.img is written to IMGDEPLOYDIR for the wks.
    local dep="${DEPLOY_DIR_IMAGE}"
    local imgdep="${IMGDEPLOYDIR}"
    local tools="${dep}/rk3506-fit-tools"
    local env="${imgdep}/${IMAGE_LINK_NAME}.${DM_VERITY_IMAGE_TYPE}.verity.env"
    local keydir="${RK3506_TEE_FIT_KEY_DIR}"
    local base_ird="${dep}/${VERITY_INITRAMFS}"
    [ -f "$env" ]            || bbfatal "verity: $env missing (dm_verity_hash didn't run)"
    [ -x "$tools/mkimage" ]  || bbfatal "verity: FIT tools not staged at $tools (u-boot do_deploy)"
    [ -f "$keydir/dev.key" ] || bbfatal "verity: RK3506_TEE_FIT_KEY_DIR/dev.key missing"
    [ -f "$base_ird" ]       || bbfatal "verity: initramfs $base_ird missing"

    local ROOT_HASH SALT DATA_BLOCK_SIZE HASH_BLOCK_SIZE DATA_BLOCKS HASH_ALGO
    . "$env"

    local work="${WORKDIR}/verity-bootimg"
    rm -rf "$work"; cp -a "$tools" "$work"; install -d "$work/images"

    # Bake the authenticated verity.conf into a copy of the initramfs cpio.
    # data=mmcblk0p2, hash=mmcblk0p3 (the GPT verity .wks layout).
    local idir="$work/initramfs"; rm -rf "$idir"; mkdir -p "$idir/etc"
    ( cd "$idir" && zcat "$base_ird" | cpio -idmu --quiet )
    cat > "$idir/etc/verity.conf" <<EOF
NAME=vroot
DATA_DEV=/dev/mmcblk0p2
HASH_DEV=/dev/mmcblk0p3
DATA_BLOCK_SIZE=${DATA_BLOCK_SIZE}
HASH_BLOCK_SIZE=${HASH_BLOCK_SIZE}
DATA_BLOCKS=${DATA_BLOCKS}
HASH_ALGO=${HASH_ALGO}
ROOT_HASH=${ROOT_HASH}
SALT=${SALT}
EOF
    ( cd "$idir" && find . | sort | cpio -o -H newc --quiet | gzip -9 > "$work/images/ramdisk" )
    bbnote "verity: baked /etc/verity.conf (root hash ${ROOT_HASH}) into the FIT initramfs"

    # FIT: kernel = zImage; fdt + resource(rk-kernel.dtb) = the UNMODIFIED kernel
    # DTB (bootargs left short — the initramfs handles root, no dm-mod.create).
    cp "${dep}/zImage" "$work/images/kernel"
    cp "${dep}/${KDTB_FIRST}" "$work/images/dtb"
    cp "$work/images/dtb" "$work/images/rk-kernel.dtb"
    ( cd "$work" && ./resource_tool --pack --image="$work/images/second" "$work/images/rk-kernel.dtb" )
    ( cd "$work" && srctree="$work" ./arch/arm/mach-rockchip/make_fit_boot.sh > "$work/boot.img.its" )
    # Real load addrs for verified boot (placeholders aren't fixed up; see plan).
    sed -i -e 's/0xffffff00/0x0d000000/g' -e 's/0xffffff01/0x08000000/g' \
           -e 's/0xffffff02/0x0e000000/g' "$work/boot.img.its"
    "$work/mkimage" -f "$work/boot.img.its" -k "$keydir" -E -p 0x1200 -r "${imgdep}/boot-verity.img"
    bbnote "Phase 4: signed boot-verity.img (initramfs-activated dm-verity)"
}
do_image_wic[prefuncs] += "${@'rk3506_verity_bootimg' if d.getVar('RK3506_ROOTFS_VERITY') == '1' else ''}"
