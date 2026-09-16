## What & why
<!-- One or two sentences. Link the issue if there is one. -->

## How it was tested
<!-- Be specific: a clean build and a hardware boot are different claims. -->
- [ ] `bitbake luckfox-image-minimal` completes clean
- [ ] Tested on target hardware — carrier revision and SoM variant:
- [ ] `pre-commit run --all-files` passes (recipe changes)

## Checklist
- [ ] No secrets, keys, WiFi credentials or signing material committed
- [ ] Docs updated, and `CHANGELOG.md` (Unreleased) has an entry
- [ ] No new oelint warnings; any new suppression in `.oelint.cfg` has a `_why_*` rationale
- [ ] Security impact considered (new interfaces, dependencies, default config)
