SUMMARY = "Foxbridge WiFi bringup — placeholder wpa_supplicant config + systemd unit"
DESCRIPTION = "Installs /etc/wpa_supplicant/wpa_supplicant-wlan.conf (user \
edits to add credentials), a systemd unit that auto-detects the first wl* \
interface and brings it up with wpa_supplicant -Dwext + udhcpc. The unit is \
enabled by default but refuses to start while the config still contains the \
CHANGEME placeholder, so a fresh image boots cleanly without spurious failures."
HOMEPAGE = "https://github.com/OOHehir/luckfox-yocto"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://wpa_supplicant-wlan.conf \
    file://foxbridge-wifi.service \
    file://foxbridge-wifi-up.sh \
    file://r8188eu.conf \
"

inherit systemd

SYSTEMD_SERVICE:${PN} = "foxbridge-wifi.service"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"

do_install() {
    install -d ${D}${sysconfdir}/wpa_supplicant
    install -m 0600 ${WORKDIR}/wpa_supplicant-wlan.conf ${D}${sysconfdir}/wpa_supplicant/wpa_supplicant-wlan.conf

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${WORKDIR}/foxbridge-wifi.service ${D}${systemd_system_unitdir}/foxbridge-wifi.service

    install -d ${D}${libexecdir}
    install -m 0755 ${WORKDIR}/foxbridge-wifi-up.sh ${D}${libexecdir}/foxbridge-wifi-up

    install -d ${D}${sysconfdir}/modprobe.d
    install -m 0644 ${WORKDIR}/r8188eu.conf ${D}${sysconfdir}/modprobe.d/r8188eu.conf
}

FILES:${PN} = " \
    ${sysconfdir}/wpa_supplicant/wpa_supplicant-wlan.conf \
    ${sysconfdir}/modprobe.d/r8188eu.conf \
    ${systemd_system_unitdir}/foxbridge-wifi.service \
    ${libexecdir}/foxbridge-wifi-up \
"

RDEPENDS:${PN} = "busybox-udhcpc wpa-supplicant"

CONFFILES:${PN} = "${sysconfdir}/wpa_supplicant/wpa_supplicant-wlan.conf"
