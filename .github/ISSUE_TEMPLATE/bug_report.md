---
name: Bug report
about: Something is broken
labels: bug
---

**Summary**

**Version / commit**

**Hardware** (carrier revision, SoM variant — Core3506-0808 or -0000, panel, WiFi dongle)

**Build configuration** (relevant `local.conf` lines: `MACHINE_FEATURES`, `LUCKFOX_OTA`, `LUCKFOX_DOOM`, `OPTEE_PROVIDER`)

**Steps to reproduce**
1.

**Expected vs actual behaviour**

**Logs** (serial console from power-on, `dmesg`, bitbake output — attach rather than paste if long)

> Check the README's *Known Limitations* first: the garbled first second of boot,
> `debug-tweaks` defaults, r8188eu WiFi flakiness and the non-functional SD slot
> on Core3506-0808 are all known and documented.

> Security vulnerability? Don't file it here — use *Security → Report a
> vulnerability* for a private advisory.
