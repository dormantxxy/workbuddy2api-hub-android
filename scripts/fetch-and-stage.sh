#!/usr/bin/env bash
# Runs inside GitHub Actions (ubuntu). Stages into app/src/main/assets/:
#   assets/bin/  -> python3.14 (aarch64, Termux)
#   assets/lib/  -> libpython3.14.so + full transitive NEEDED closure + stdlib
#   assets/app/  -> wb_*.py + dashboard.html (upstream source)
set -euo pipefail

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/ardeyouxipianyi/workbuddy2api-hub.git}"
TERMUX_BASE="https://packages.termux.dev/apt/termux-main"
ARCH="aarch64"
ASSETS="app/src/main/assets"
WORK="$(mktemp -d)"

echo "== 1. clone upstream source (runtime .py + dashboard only) =="
git clone --depth 1 "$UPSTREAM_URL" "$WORK/src"
mkdir -p "$ASSETS/app"
cp "$WORK"/src/wb_*.py "$ASSETS/app/"
cp "$WORK"/src/dashboard.html "$ASSETS/app/"
echo "  staged $(ls "$ASSETS/app" | wc -l) files"

echo "== 2. fetch termux Packages index =="
curl -fsSL "$TERMUX_BASE/dists/stable/main/binary-$ARCH/Packages.gz" -o "$WORK/Packages.gz"
gunzip -c "$WORK/Packages.gz" > "$WORK/Packages"

deb_filename() {
  awk -v p="$1" '
    /^Package: /{name=substr($0,10)}
    /^Filename: /{fn=substr($0,11)}
    /^$/ { if(name==p){print fn; done=1; exit} name=""; fn="" }
    END { if(!done && name==p){print fn} }
  ' "$WORK/Packages"
}

echo "== 3. download python + all Depends libs =="
# python's Depends (from Termux Packages) covers the C-extension closure.
PKGS="python gdbm libandroid-posix-semaphore libandroid-support libbz2 libcrypt libexpat libffi liblzma libsqlite ncurses ncurses-ui-libs openssl readline zlib zstd"
mkdir -p "$WORK/ext"
for pkg in $PKGS; do
  fn="$(deb_filename "$pkg")"
  if [ -z "$fn" ]; then echo "  !! missing pkg: $pkg"; continue; fi
  echo "  - $pkg"
  curl -fsSL "$TERMUX_BASE/$fn" -o "$WORK/$pkg.deb"
  dpkg-deb -x "$WORK/$pkg.deb" "$WORK/ext"
done

echo "== 4. stage python binary =="
mkdir -p "$ASSETS/bin" "$ASSETS/lib"
PY_BIN="$(find "$WORK/ext" -type f -path '*/usr/bin/python3.14' | head -1)"
echo "  python: $PY_BIN"
cp "$PY_BIN" "$ASSETS/bin/python3.14"
chmod +x "$ASSETS/bin/python3.14"

echo "== 5. stage stdlib (lib/python3.14) =="
STD="$(find "$WORK/ext" -type d -path '*/usr/lib/python3.14' | head -1)"
echo "  stdlib: $STD"
cp -a "$STD" "$ASSETS/lib/python3.14"
# drop caches to shrink APK
find "$ASSETS/lib/python3.14" -name '__pycache__' -type d -prune -exec rm -rf {} + 2>/dev/null || true
find "$ASSETS/lib/python3.14" -name '*.pyc' -delete 2>/dev/null || true
rm -rf "$ASSETS/lib/python3.14/test" "$ASSETS/lib/python3.14/idlelib" \
       "$ASSETS/lib/python3.14/tkinter" "$ASSETS/lib/python3.14/turtledemo" \
       "$ASSETS/lib/python3.14/ensurepip" 2>/dev/null || true

echo "== 6. stage libpython + recursive NEEDED closure =="
copy_needed() {
  declare -A SEEN
  local queue=("$ASSETS/bin/python3.14")
  # libpython is dlopened by the launcher; seed it explicitly
  local lp
  lp="$(find "$WORK/ext" -type f -name 'libpython3.14.so*' | head -1)"
  if [ -n "$lp" ]; then cp -L "$lp" "$ASSETS/lib/"; queue+=("$ASSETS/lib/libpython3.14.so"); echo "  + libpython"; fi
  local q2
  q2="$(find "$WORK/ext" -type f -name 'libpython3.so*' | head -1)"
  [ -n "$q2" ] && { cp -L "$q2" "$ASSETS/lib/"; echo "  + libpython3.so"; }

  while [ ${#queue[@]} -gt 0 ]; do
    local f="${queue[0]}"; queue=("${queue[@]:1}")
    local so
    while IFS= read -r so; do
      [ -z "$so" ] && continue
      [ -n "${SEEN[$so]:-}" ] && continue
      SEEN[$so]=1
      # already staged?
      [ -e "$ASSETS/lib/$so" ] && continue
      local src
      src="$(find "$WORK/ext" -path '*/usr/lib/*' -name "$so" 2>/dev/null | head -1)"
      if [ -n "$src" ]; then
        cp -L "$src" "$ASSETS/lib/$so"; queue+=("$ASSETS/lib/$so"); echo "  + $so"
      else
        echo "  . $so (system-provided)"
      fi
    done < <(readelf -d "$f" 2>/dev/null | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p')
  done
}
copy_needed

echo "== 7. also stage lib-dynload C-extension deps =="
DYN="$ASSETS/lib/python3.14/lib-dynload"
if [ -d "$DYN" ]; then
  python3 - "$ASSETS/lib" "$WORK/ext" <<'PY'
import os, subprocess, sys, shutil
libdir, ext = sys.argv[1], sys.argv[2]
dyn = os.path.join(libdir, "python3.14", "lib-dynload")
need, seen = [], set()
for root, _, files in os.walk(dyn):
    for fn in files:
        if not fn.endswith(".so"): continue
        out = subprocess.run(["readelf","-d",os.path.join(root,fn)], capture_output=True, text=True).stdout
        for line in out.splitlines():
            if "NEEDED" in line and "[" in line:
                so = line.split("[",1)[1].split("]",1)[0]
                if so not in seen: seen.add(so); need.append(so)
# find each in ext tree and copy if not already present
for so in need:
    dst = os.path.join(libdir, so)
    if os.path.exists(dst): continue
    found = None
    for root, _, files in os.walk(ext):
        if "/usr/lib/" in root and so in files:
            found = os.path.join(root, so); break
    if found:
        shutil.copyfile(found, dst); print("  +", so)
    else:
        print("  .", so, "(system)")
PY
fi

find "$ASSETS" -type d -empty -delete 2>/dev/null || true

echo "== 8. summary =="
du -sh "$ASSETS/app" "$ASSETS/bin" "$ASSETS/lib" 2>/dev/null || true
echo "bin:  $(ls "$ASSETS/bin" 2>/dev/null | wc -l) files"
echo "libs: $(ls "$ASSETS/lib"/*.so* 2>/dev/null | wc -l) shared libs"
