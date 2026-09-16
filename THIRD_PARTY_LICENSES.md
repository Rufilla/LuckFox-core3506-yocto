# Third-party licenses

Source components fetched and built into this BSP that carry their own license.
Rockchip proprietary boot blobs (rkbin: DDR init, SPL, USB plug, and the OP-TEE
vendor blob used only as the `OPTEE_PROVIDER="rkbin"` fallback) are covered by
the Rockchip commercial license accepted via `LICENSE_FLAGS_ACCEPTED` and are
not listed here.

| Component | Upstream | Pinned rev | License |
|-----------|----------|-----------|---------|
| OP-TEE OS (secure world, `tee.bin`) | https://github.com/OP-TEE/optee_os | `298746f9e2907886dcf68a0586b9fb0671ad7043` (master) | BSD-2-Clause |
| OP-TEE client (`libteec`, `tee-supplicant`) | https://github.com/OP-TEE/optee_client | `9f5e90918093c1d1cd264d8149081b64ab7ba672` (master) | BSD-2-Clause |
| Linux kernel | https://github.com/rockchip-linux/kernel | `d2b4477a1df699e6639e83837c7dc45ea1d1d73f` (develop-6.1) | GPL-2.0-only (with syscall note) |
| U-Boot | https://github.com/rockchip-linux/u-boot | `b14196eade471bbc000c368f8555f2a2a1ecc17d` (next-dev) | GPL-2.0-or-later |
| PicoClaw (AI agent, **prebuilt binary**) | https://github.com/sipeed/picoclaw | `v0.2.6` release tarball, armv7 | MIT |
| doomgeneric (DOOM demo payload) | https://github.com/ozkl/doomgeneric | `dcb7a8dbc7a16ce3dda29382ac9aae9d77d21284` | GPL-2.0-only |
| Freedoom (DOOM WAD assets) | https://github.com/freedoom/freedoom | `v0.12.1` release zip | BSD-3-Clause |
| `golang.org/x/sys` (vendored into foxbridge-mcp) | https://cs.opensource.google/go/x/sys | `v0.18.0` | BSD-3-Clause |
| `go-gpiocdev` (vendored into foxbridge-mcp) | https://github.com/warthog618/go-gpiocdev | `v0.9.1` | MIT |

Both vendored Go modules ship in-tree under
`meta-luckfox-distro/recipes-support/foxbridge-mcp/files/src/vendor/`, each with
its upstream `LICENSE` file alongside. `foxbridge-mcp` builds offline from that
vendor directory; no Go modules are fetched at build time.

## PicoClaw — prebuilt binary, no source-build provenance

PicoClaw is the one component consumed as an **upstream release binary** rather
than built from source. The recipe documents why (upstream's `go.mod` requires
Go 1.25, three minor versions ahead of what poky scarthgap ships). The binary is
statically linked, stripped by upstream, and pinned by SHA256
(`a666ef82…`) which is verifiable against upstream's published checksums.

Consequences worth knowing: the layer carries no build provenance for it, and
an SBOM generated from this build will describe the tarball rather than its
dependency tree. Upstream also warns that PicoClaw is pre-1.0 and may have
unresolved security issues — it is not enabled until the operator clears the
`CHANGEME_` markers in `/etc/default/picoclaw`.

## OP-TEE OS — BSD-2-Clause

OP-TEE OS is licensed BSD-2-Clause. The default secure world (`tee.bin`) is
built from the public upstream tree at the pinned rev above (RK3506
`plat-rockchip` flavor, RFC #7820, plus the `CFG_8250_UART_FLUSH_TIMEOUT`
shared-UART0 flush fix). The full license text ships in the upstream tree at
`LICENSE` (`LIC_FILES_CHKSUM = "file://LICENSE;md5=c1f21c4f72f372ef38a5a4aee55ec173"`).
