SUMMARY = "Foxbridge live system dashboard"
DESCRIPTION = "Real-time system dashboard with live gauges, network throughput, \
and LED control — served via lighttpd CGI on foxbridge.local"
HOMEPAGE = "https://github.com/OOHehir/luckfox-yocto"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://dashboard.html \
    file://api.cgi \
    file://status.cgi \
    file://lighttpd-status.conf \
"

do_install() {
    install -d ${D}/www/pages

    # Live dashboard — installed as dashboard.html; postinst copies to index.html
    # to avoid file clash with lighttpd's default index.html
    install -m 0644 ${WORKDIR}/dashboard.html ${D}/www/pages/dashboard.html

    # JSON API endpoint
    install -m 0755 ${WORKDIR}/api.cgi ${D}/www/pages/api.cgi

    # Legacy status page (still accessible at /status.cgi)
    install -m 0755 ${WORKDIR}/status.cgi ${D}/www/pages/status.cgi

    install -d ${D}${sysconfdir}/lighttpd.d
    install -m 0644 ${WORKDIR}/lighttpd-status.conf ${D}${sysconfdir}/lighttpd.d/status.conf
}

# Replace lighttpd's default index.html with our dashboard
pkg_postinst:${PN}() {
    cp $D/www/pages/dashboard.html $D/www/pages/index.html
}

FILES:${PN} = "/www/pages ${sysconfdir}/lighttpd.d"

RDEPENDS:${PN} = "lighttpd lighttpd-module-cgi"
