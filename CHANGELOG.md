<!-- Format: https://keepachangelog.com/en/1.1.0/ ; versions: https://semver.org -->
# Changelog

All notable changes to the LuckFox Core3506 Yocto layers are documented here.
Each released version also has fuller notes under `release/<version>/`.

## [Unreleased]

### Added
- `LICENSE` (MIT), `CONTRIBUTING.md`, `CHANGELOG.md`, `CODEOWNERS` and GitHub
  pull-request / issue templates.
- `.gitattributes` marking the vendored Go tree as `linguist-vendored`.

### Changed
- The README now carries the security defaults, the no-support position and the
  vulnerability reporting route directly.
- `meta-rufilla-lucerna` is now optional. `setup-layers.json`,
  `bblayers.conf.sample` and `local.conf.sample` no longer require it; stock
  `cve-check` is offered as the public equivalent.
- `THIRD_PARTY_LICENSES.md` now lists every externally fetched component,
  including the vendored Go modules and the prebuilt PicoClaw binary.

### Removed
- Internal bench-rig scripts and the OP-TEE upstreaming handoff document, which
  depended on tooling and repositories that are not public.

## [1.3.0] - 2026-06-11

### Added
- 7" MIPI-DSI panel end to end: display, GT9271 capacitive touch (poll mode),
  and a psplash boot splash. Opt in with `MACHINE_FEATURES += "screen"`.
- Framebuffer DOOM (`doomgeneric` + Freedoom) as a demo payload (`LUCKFOX_DOOM=1`).
- SWUpdate A/B OTA, milestone M1: dual-slot rootfs and boot, U-Boot env slot
  selection, automatic rollback, `.swu` generation (`LUCKFOX_OTA=1`). Opt-in.

### Known issues
- SBOM generation disabled pending a Python 3.12 fork-deadlock fix.

## [1.2.0] - 2026-04-21

### Added
- `foxbridge-mcp`: an MCP server giving the on-board PicoClaw agent safe,
  unprivileged access to LEDs, GPIOs and read-only system/network state.
- PicoClaw recipe versioned in-tree.

## [1.1.0] - 2026-04-18

### Added
- Live status dashboard (`foxbridge-status`) over lighttpd and Avahi/mDNS.
- Hardware benchmarks and evaluation tooling.

### Fixed
- WiFi routing fix; documentation corrections across the build and validation docs.

## [1.0.0] - 2026-04-14

### Added
- First hardware-validated release. Boots end to end on the Foxbridge Rev A
  carrier with a Core3506-0808 SoM.
- Vendor kernel 6.1.x and U-Boot 2017.09 with distro-boot patches.
- Ethernet (RMII/gmac0), USB1 host, USB WiFi via RTL8188EU.
- systemd, SSH, `foxbridge-wifi` auto-bringup with `CHANGEME` guard.

[Unreleased]: https://github.com/Rufilla/LuckFox-core3506-yocto/compare/v1.3.0...HEAD
[1.3.0]: https://github.com/Rufilla/LuckFox-core3506-yocto/releases/tag/v1.3.0
[1.2.0]: https://github.com/Rufilla/LuckFox-core3506-yocto/releases/tag/v1.2.0
[1.1.0]: https://github.com/Rufilla/LuckFox-core3506-yocto/releases/tag/v1.1.0
[1.0.0]: https://github.com/Rufilla/LuckFox-core3506-yocto/releases/tag/v1.0.0
