# EXPERIMENTAL — Boot image assembly class for RK3506
# Handles the Rockchip-specific boot image layout for wic images.
#
# Rockchip boot chain:
#   BootROM -> idbloader.img (sector 64) -> uboot.img -> kernel FIT -> Linux
#
# Standard Yocto wic bootloader plugins do NOT work with Rockchip's boot chain.
# This class ensures the correct images are deployed for the wks file to reference.

# The U-Boot recipe deploys idbloader.img and uboot.img to DEPLOY_DIR_IMAGE.
# The wks file uses rawcopy to write them at the correct sector offsets.

# Ensure boot images are built before image assembly
do_image_wic[depends] += " \
    u-boot-rockchip-rk3506:do_deploy \
    virtual/kernel:do_deploy \
"

# Standard Rockchip eMMC/SD image type
IMAGE_FSTYPES:append = " wic"

# Do not use standard Yocto bootloader image types — they produce non-bootable
# images for Rockchip platforms. Only use wic with our custom wks files.
IMAGE_BOOT_FILES ?= ""
