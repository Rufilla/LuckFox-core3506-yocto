# Minimal LuckFox image for SD card boot.
# Flash with: dd if=<image>.wic of=/dev/sdX bs=4M status=progress

require luckfox-image-minimal.inc

WKS_FILE = "luckfox-rk3506-sdcard.wks"
