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

echo "== 6. stage ALL shared libs from extracted debs =="
# Termux debs unpack to ./data/data/com.termux/files/usr/lib/...
# Copy every top-level .so / .so.* so the C-extension closure is complete.
mkdir -p "$ASSETS/lib"
while IFS= read -r src; do
  dir="$(dirname "$src")"
  case "$dir" in
    */usr/lib) ;;            # only libraries directly in usr/lib/
    *) continue ;;           # skip engines-3/, ossl-modules/, python3.14/ subtree
  esac
  cp -Lf "$src" "$ASSETS/lib/$(basename "$src")"
done < <(find "$WORK/ext" -maxdepth 99 -path '*/usr/lib/*' ! -type d \( -name '*.so' -o -name '*.so.*' \) 2>/dev/null)
echo "  staged libs: $(ls "$ASSETS/lib"/*.so* 2>/dev/null | wc -l)"

echo "== 7. recursive NEEDED closure top-up for every staged .so =="
python3 - "$ASSETS/lib" "$WORK/ext" <<'PYEOF'
import os, subprocess, sys, shutil
libdir, ext = sys.argv[1], sys.argv[2]
need, seen = [], set()
def needed(path):
    out = subprocess.run(["readelf","-d",path], capture_output=True, text=True).stdout
    return [l.split("[",1)[1].split("]",1)[0] for l in out.splitlines() if "NEEDED" in l and "[" in l]
queue = [os.path.join(libdir,f) for f in os.listdir(libdir)]
while queue:
    f = queue.pop(0)
    for so in needed(f):
        if so in seen: continue
        seen.add(so)
        dst = os.path.join(libdir, so)
        if os.path.exists(dst):
            queue.append(dst); continue
        found = None
        for root,_,files in os.walk(ext):
            if os.path.basename(root) == "lib" and so in files:
                found = os.path.join(root, so); break
        if found:
            shutil.copyfile(found, dst); queue.append(dst); print("  +", so)
        else:
            print("  .", so, "(system)")
PYEOF

find "$ASSETS" -type d -empty -delete 2>/dev/null || true

echo "== 8. summary =="
du -sh "$ASSETS/app" "$ASSETS/bin" "$ASSETS/lib" 2>/dev/null || true
echo "bin:  $(ls "$ASSETS/bin" 2>/dev/null | wc -l) files"
echo "libs: $(ls "$ASSETS/lib"/*.so* 2>/dev/null | wc -l) shared libs"
