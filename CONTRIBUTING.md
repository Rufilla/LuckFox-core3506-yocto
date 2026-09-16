# Contributing to LuckFox Core3506 Yocto Layers

Thanks for your interest. This repository holds the Yocto layers for the
Rockchip RK3506 / LuckFox Core3506 on the Foxbridge carrier. The companion
hardware lives at
[Rufilla/LuckFox-Core3506-Carrier](https://github.com/Rufilla/LuckFox-Core3506-Carrier).

> **This project is unsupported.** It is published as-is, with no warranty and
> no commitment to maintenance. Contributions
> are genuinely welcome, but issues and pull requests are handled on a
> best-effort basis and may go unanswered or be closed without a detailed
> review. Please don't invest heavily in a large change without asking first.

## Before you start

Please open an issue before a large change. Several things in this tree look
odd but are deliberate and documented — the vendor 6.1 kernel rather than
mainline, U-Boot 2017.09, the closed Rockchip boot blobs, and PicoClaw shipping
as a prebuilt binary. [BRIEFING.md](BRIEFING.md) explains each; a PR that
"fixes" one of them without reading it will be closed.

## Workflow

1. Branch from `main`: `fix/<short-description>` or `feature/<short-description>`.
2. Keep commits small and focused. Rebase onto `main` before opening a PR.
3. Open a pull request using the template and say what hardware you tested on.
4. A maintainer approval and a clean build are needed before merge. There is
   no review SLA; see the note above.

## Commit messages

Yocto layers follow the
[OpenEmbedded commit guidelines](https://docs.yoctoproject.org/contributor-guide/submit-changes.html):

```
<recipe-or-area>: <imperative summary, <= 72 chars>

Why the change is needed and what it does. Wrap at 72 columns.

Signed-off-by: Your Name <your.email@example.com>
```

Use the real recipe name where there is one (`linux-rockchip-rk3506:`,
`u-boot-rockchip-rk3506:`, `foxbridge-mcp:`), otherwise an area (`docs:`,
`bsp:`, `distro:`).

## Testing your change

State what you actually did. "Builds clean" and "boots on hardware" are
different claims and both are useful:

```bash
bitbake luckfox-image-minimal                    # must complete clean
bitbake -c cleansstate <recipe> && bitbake <recipe>   # after recipe edits
```

Recipe changes should pass the linter before you push:

```bash
pip3 install --user pre-commit
pre-commit install
pre-commit run --all-files
```

`.oelint.cfg` documents every suppressed rule with a `_why_*` key. If you need
to suppress a new one, add the rationale in the same style rather than silently
disabling it.

If you have the hardware, say which carrier revision and SoM variant you tested
on — Core3506-0808 (eMMC) and Core3506-0000 (no eMMC) behave differently, and
the carrier's SD slot is non-functional on the 0808 (see the README).

## Secrets and hardware data

Never commit keys, certificates, passwords, WiFi credentials or signing
material. The repository uses `CHANGEME_` placeholders for every credential
slot; keep it that way. `keys/` is gitignored because it holds development FIT
signing keys — production signing uses an HSM. If something sensitive does get
committed, say so immediately: removing it in a later commit is not enough.

## Documentation

Docs are part of the change, not a follow-up. If you alter behaviour, update
the relevant document in the same PR and add an entry to
[CHANGELOG.md](CHANGELOG.md) under *Unreleased*.

| Document | Covers |
|---|---|
| [README.md](README.md) | Overview, quick start, known limitations |
| [BRIEFING.md](BRIEFING.md) | Blobs, kernel, device tree, U-Boot, boot chain |
| [FLASHING.md](FLASHING.md) | End-user and developer flashing |
| [OTA_SWUPDATE_PLAN.md](OTA_SWUPDATE_PLAN.md) | A/B OTA design and status |

## Reporting security issues

Please do not open a public issue for a suspected vulnerability. Use GitHub's
*Security → Report a vulnerability* on this repository, which opens a private
advisory. Reports are read on a best-effort basis; this project is unsupported
and no response is guaranteed.

Note that the default image is a bring-up image: it enables `debug-tweaks`,
giving an empty root password and root SSH login. That is a documented default,
not a vulnerability report we need.

## Licence

Contributions are accepted under the MIT licence of this repository
(see [LICENSE](LICENSE)).
