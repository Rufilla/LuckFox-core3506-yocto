#!/usr/bin/env bash
# Runs poky/scripts/yocto-check-layer against each layer in this repo
# (meta-rockchip-rk3506, meta-luckfox-bsp, meta-luckfox-distro).
#
# Manual-stage pre-commit hook — needs poky cloned and a build dir
# previously initialised. See README.md § Quick Start for the one-time
# setup. Run with:
#   pre-commit run --hook-stage manual yocto-check-layer
#
# Override the poky checkout / build dir locations via env if your tree
# layout differs from the README's suggested ../poky and ../build-core3506:
#   POKY_DIR=/path/to/poky BUILD_DIR=/path/to/build pre-commit run ...

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

# Resolve poky and build dirs. Order: env override → in-tree (legacy) →
# sibling-dir convention (matches README Quick Start).
POKY_DIR="${POKY_DIR:-}"
if [ -z "${POKY_DIR}" ]; then
    if [ -d "$REPO_ROOT/sources/poky" ]; then
        POKY_DIR="$REPO_ROOT/sources/poky"
    elif [ -d "$REPO_ROOT/poky" ]; then
        POKY_DIR="$REPO_ROOT/poky"
    elif [ -d "$REPO_ROOT/../poky" ]; then
        POKY_DIR="$(cd "$REPO_ROOT/../poky" && pwd)"
    else
        echo "error: poky/ not found — clone scarthgap first (see README.md § Quick Start)." >&2
        echo "       or set POKY_DIR=/path/to/poky" >&2
        exit 1
    fi
fi

BUILD_DIR="${BUILD_DIR:-}"
if [ -z "${BUILD_DIR}" ]; then
    for candidate in \
        "$REPO_ROOT/build" \
        "$REPO_ROOT/../build-core3506" \
        "$REPO_ROOT/../build"; do
        if [ -d "$candidate" ]; then
            BUILD_DIR="$(cd "$candidate" && pwd)"
            break
        fi
    done
    if [ -z "${BUILD_DIR}" ]; then
        echo "error: build dir not found — run 'source poky/oe-init-build-env <build>' first." >&2
        echo "       or set BUILD_DIR=/path/to/build" >&2
        exit 1
    fi
fi

# shellcheck source=/dev/null
source "$POKY_DIR/oe-init-build-env" "$BUILD_DIR" >/dev/null

# yocto-check-layer accepts multiple layer paths in one invocation; running
# them together lets it cross-validate dependencies (BSP requires SoC vendor,
# distro requires BSP).
exec yocto-check-layer \
    "$REPO_ROOT/meta-rockchip-rk3506" \
    "$REPO_ROOT/meta-luckfox-bsp" \
    "$REPO_ROOT/meta-luckfox-distro"
