# Release artifacts

Each `v<version>/` directory holds the release notes, the combined USB download
loader, and a `SHA256SUMS` covering both the loader and the full eMMC image.

**The `.wic` image itself is not in this repository.** It is roughly 300–400 MB,
well over GitHub's 100 MB file limit, so it is attached to the corresponding
[GitHub release](https://github.com/Rufilla/LuckFox-core3506-yocto/releases) as
a downloadable asset. `SHA256SUMS` lists it so you can verify the download:

```bash
# from the release/<version>/ directory, with the .wic downloaded alongside
sha256sum -c SHA256SUMS
```

`sha256sum -c` reports `No such file or directory` for the `.wic` until you have
downloaded it from the release page. That is expected, not a corrupt release.

The loader binary is identical across v1.0.0, v1.1.0 and v1.2.0 (same upstream
rkbin blobs); v1.3.0 differs. Flashing instructions are in
[FLASHING.md](../FLASHING.md).
