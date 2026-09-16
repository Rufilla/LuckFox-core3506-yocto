# Base packagegroup for all LuckFox boards.
# Pulled into luckfox-image-minimal and all larger images.

SUMMARY = "LuckFox base system packages"
DESCRIPTION = "Aggregator pulled into all LuckFox images. Provides core \
networking (iproute2, ethtool, curl), filesystem and storage tooling \
(util-linux, e2fsprogs), bus-level diagnostics (i2c-tools, usbutils), \
SSH server + client + SFTP, and stress-ng for bringup load tests."
LICENSE = "MIT"

inherit packagegroup

RDEPENDS:${PN} = " \
    curl \
    e2fsprogs \
    ethtool \
    i2c-tools \
    iproute2 \
    openssh-keygen \
    openssh-sftp-server \
    openssh-ssh \
    openssh-sshd \
    stress-ng \
    usbutils \
    util-linux \
"

# Conditional: add wpa-supplicant and wireless tools if WiFi is in MACHINE_FEATURES
RRECOMMENDS:${PN} = " \
    ${@bb.utils.contains('MACHINE_FEATURES', 'wifi', 'wpa-supplicant iw wireless-regdb', '', d)} \
    ${@bb.utils.contains('MACHINE_FEATURES', 'ethernet', 'ethtool', '', d)} \
"
