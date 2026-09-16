SUMMARY = "Core3506 dm-verity activation initramfs"
DESCRIPTION = "Tiny initramfs that activates the dm-verity root (via the \
self-contained dm-verity-open DM-ioctl tool, reading an authenticated baked-in \
verity.conf) and switch_root's into it — so the kernel cmdline needs no long \
quoted dm-mod.create. Goes in the signed boot.img FIT ramdisk node."
LICENSE = "MIT"

# busybox provides /bin/sh + mount/switch_root/sleep/seq/mkdir; dm-verity-init
# provides /init + /sbin/dm-verity-open. base-files gives the fs layout.
PACKAGE_INSTALL = "busybox dm-verity-init base-files"

# Keep it tiny — no rootfs features, no kernel.
IMAGE_FEATURES = ""
IMAGE_LINGUAS = ""
IMAGE_NAME_SUFFIX ?= ""
PACKAGE_EXCLUDE = "kernel-image-*"

IMAGE_FSTYPES = "cpio.gz"
# The machine sets IMAGE_FSTYPES="wic ext4"; drop those for the initramfs (no
# wic/partition layout — this is a cpio for the FIT ramdisk node).
IMAGE_FSTYPES:remove = "wic ext4"
WKS_FILE = ""
inherit core-image

IMAGE_ROOTFS_SIZE = "8192"
IMAGE_ROOTFS_EXTRA_SPACE = "0"

COMPATIBLE_MACHINE = "luckfox-core3506"

# Only used when Rufilla's internal `meta-rufilla-lucerna` layer is present (it
# is optional and not public — see local.conf.sample). When inherited it writes
# CVE artifacts to a FIXED deploy path (lucerna/cve-aggregated.json …), so two
# image recipes collide in the shared DEPLOY_DIR_IMAGE. Give this initramfs its
# own subdir. Harmless no-op when the layer is absent.
RUF_LUCERNA_PATH = "lucerna-initramfs"
