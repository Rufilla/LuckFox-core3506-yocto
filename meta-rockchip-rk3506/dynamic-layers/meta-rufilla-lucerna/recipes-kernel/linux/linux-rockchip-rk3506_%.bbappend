# `lucerna-linux.inc` ships in meta-rufilla-lucerna and is only available
# when that layer is in BBLAYERS — which is also the only condition under
# which this bbappend is activated (see BBFILES_DYNAMIC in ../../../conf/layer.conf).
# nooelint: oelint.file.requirenotfound
require recipes-kernel/linux/lucerna-linux.inc
