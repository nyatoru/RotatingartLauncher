#!/usr/bin/env bash
# Apply RotatingArtLauncher native patches to vendored submodules.
# Currently: FNA3D ES3 GetData fallback (fixes tModLoader
# OPENGL_GetTextureData2D supports_NonES3 assertion on Android ES3).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FNA3D_DIR="$ROOT/core/libs/FNA3D"
PATCH="$ROOT/core/patches/fna3d-es3-getdata.patch"

if [ ! -d "$FNA3D_DIR" ]; then
  echo "FNA3D submodule not checked out at $FNA3D_DIR, skipping."
  exit 0
fi
if [ ! -f "$PATCH" ]; then
  echo "Patch not found: $PATCH"
  exit 1
fi

cd "$FNA3D_DIR"
if grep -q "OPENGL_INTERNAL_GetTextureData2D_ES3" src/FNA3D_Driver_OpenGL.c 2>/dev/null; then
  echo "FNA3D ES3 patch already applied."
  exit 0
fi

if git apply --check "$PATCH" 2>/dev/null; then
  git apply "$PATCH"
  echo "FNA3D ES3 patch applied via git apply."
elif command -v patch >/dev/null 2>&1 && patch -p1 --forward -i "$PATCH"; then
  echo "FNA3D ES3 patch applied via patch."
else
  echo "Failed to apply FNA3D ES3 patch."
  exit 1
fi
