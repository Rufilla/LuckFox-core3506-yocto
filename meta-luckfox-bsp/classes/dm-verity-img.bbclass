# dm-verity rootfs hash generation.
#
# Vendored + adapted from poky's dm-verity-img.bbclass. This poky (5.0.17) ships
# neither that class nor cryptsetup-native (no meta-openembedded), and the owner
# chose to vendor rather than add a layer — so we drive the BUILD HOST's
# `veritysetup` (whitelisted via HOSTTOOLS) instead of a -native recipe. For a
# reproducible production build, replace the host tool with cryptsetup-native
# (needs meta-openembedded) — tracked in the plan.
#
# Gated by RK3506_ROOTFS_VERITY. When "1", for each verity fstype this runs
# `veritysetup format` on the built rootfs image and emits, alongside it:
#   <image>.<fstype>.verity        the hash tree (Merkle) device
#   <image>.<fstype>.verity.env    KEY=VALUE params (ROOT_HASH, SALT, block
#                                  sizes, data/hash block counts) for the
#                                  image-side boot.img roothash injection (the
#                                  root hash goes into the signed DTB bootargs).
# A FIXED salt makes the root hash deterministic for a given rootfs (reproducible
# within a build); swap for a per-build random salt if desired.

# Host veritysetup (no cryptsetup-native available). HOSTTOOLS only symlinks
# tools found on bitbake's host PATH, which omits /usr/sbin where veritysetup
# lives — so the task resolves the binary by explicit path (see dm_verity_hash).

# Which rootfs fstypes to verity-protect (ext4 for RK3506).
DM_VERITY_IMAGE_TYPE ?= "ext4"
# Fixed salt -> deterministic root hash for a given rootfs (32 bytes hex).
DM_VERITY_SALT ?= "b2a7d5aff9fc794d297deaceb36f71b25da418b403764e221801ef31dd95cf3d"

python __anonymous() {
    if d.getVar('RK3506_ROOTFS_VERITY') != '1':
        return
    # Run the verity hashing after each selected fstype image is created.
    for fstype in (d.getVar('DM_VERITY_IMAGE_TYPE') or '').split():
        d.appendVarFlag('do_image_%s' % fstype, 'postfuncs', ' dm_verity_hash')
        d.appendVarFlag('do_image_%s' % fstype, 'depends',
                        ' virtual/fakeroot-native:do_populate_sysroot')
}

dm_verity_hash () {
    # NB: IMAGE_NAME / IMAGE_LINK_NAME already include the ".rootfs"
    # (IMAGE_NAME_SUFFIX) — the deployed file is ${IMAGE_NAME}.<type>, do NOT
    # append the suffix again.
    local base="${IMAGE_NAME}.${DM_VERITY_IMAGE_TYPE}"
    local img="${IMGDEPLOYDIR}/${base}"
    [ -f "$img" ] || { bbfatal "dm-verity: rootfs image $img not found"; }

    local hashimg="${img}.verity"
    local envf="${img}.verity.env"
    rm -f "$hashimg" "$envf"

    # Resolve the host veritysetup (not on the sanitized task PATH; usually
    # /usr/sbin). Dev-build expedient — production: cryptsetup-native.
    local vs=""
    for p in /usr/sbin/veritysetup /sbin/veritysetup /usr/bin/veritysetup /bin/veritysetup; do
        [ -x "$p" ] && { vs="$p"; break; }
    done
    [ -n "$vs" ] || bbfatal "dm-verity: host veritysetup not found (install cryptsetup-bin)"

    # Format: build the Merkle hash tree for $img into $hashimg, fixed salt.
    local out
    out=$($vs format --salt=${DM_VERITY_SALT} "$img" "$hashimg") || \
        bbfatal "dm-verity: veritysetup format failed: $out"

    local roothash datablk hashblk datablocks
    roothash=$(echo "$out" | awk '/Root hash:/   {print $NF}')
    datablk=$(echo  "$out" | awk '/Data block size:/ {print $NF}')
    hashblk=$(echo  "$out" | awk '/Hash block size:/ {print $NF}')
    datablocks=$(echo "$out" | awk '/Data blocks:/  {print $NF}')
    [ -n "$roothash" ] || bbfatal "dm-verity: no root hash parsed:\n$out"

    cat > "$envf" <<EOF
ROOT_HASH=$roothash
SALT=${DM_VERITY_SALT}
DATA_BLOCK_SIZE=$datablk
HASH_BLOCK_SIZE=$hashblk
DATA_BLOCKS=$datablocks
HASH_ALGO=sha256
DATA_IMAGE=$base
HASH_IMAGE=$base.verity
EOF
    bbnote "dm-verity: root hash $roothash ($datablocks x $datablk-byte blocks)"

    # Stable symlinks for the wic + the image-side boot.img roothash injection.
    local lbase="${IMAGE_LINK_NAME}.${DM_VERITY_IMAGE_TYPE}"
    ln -sf "$base.verity"     "${IMGDEPLOYDIR}/${lbase}.verity"
    ln -sf "$base.verity.env" "${IMGDEPLOYDIR}/${lbase}.verity.env"
}
