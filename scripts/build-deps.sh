#!/bin/bash
# Cross-build OpenSSL libcrypto and libplist for one Android ABI into
# app/src/main/cpp/deps/. Already-built artifacts are committed, so this
# is only needed to upgrade or rebuild them, or to add an ABI.
#
#   scripts/build-deps.sh [arm64-v8a|armeabi-v7a]   (default: arm64-v8a)
#
# NDK: $ANDROID_NDK_ROOT, else .tools/ndk-r27d, else $ANDROID_HOME/ndk/27.3.13750724
# (r27d; download: https://dl.google.com/android/repository/android-ndk-r27d-darwin.zip).
#
# OpenSSL's openssl/configuration.h differs between 32- and 64-bit targets (BN_ULONG
# size), so each ABI gets its own copy under openssl/include-abi/<abi>/; CMake puts it
# ahead of the shared headers.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
tools="$repo_dir/.tools"
out="$repo_dir/app/src/main/cpp/deps"
api=29
abi="${1:-arm64-v8a}"
case "$abi" in
  arm64-v8a)   openssl_target=android-arm64; host=aarch64-linux-android; cc_prefix=aarch64-linux-android ;;
  armeabi-v7a) openssl_target=android-arm;   host=arm-linux-androideabi; cc_prefix=armv7a-linux-androideabi ;;
  *) echo "unsupported ABI: $abi" >&2; exit 1 ;;
esac

ndk="${ANDROID_NDK_ROOT:-}"
[ -n "$ndk" ] || { [ -d "$tools/ndk-r27d" ] && ndk="$tools/ndk-r27d"; } || true
[ -n "$ndk" ] || ndk="${ANDROID_HOME:?set ANDROID_NDK_ROOT or ANDROID_HOME}/ndk/27.3.13750724"
[ -d "$ndk" ] || { echo "NDK not found: $ndk" >&2; exit 1; }

mkdir -p "$tools"
[ -f "$tools/openssl.tar.gz" ] || curl -sL -o "$tools/openssl.tar.gz" \
  https://github.com/openssl/openssl/releases/download/openssl-3.0.16/openssl-3.0.16.tar.gz
[ -f "$tools/libplist.tar.gz" ] || curl -sL -o "$tools/libplist.tar.gz" \
  https://github.com/libimobiledevice/libplist/releases/download/2.6.0/libplist-2.6.0.tar.bz2
rm -rf "$tools/openssl-3.0.16" "$tools/libplist-2.6.0" "$tools/libplist-install"
tar xf "$tools/openssl.tar.gz" -C "$tools"
tar xf "$tools/libplist.tar.gz" -C "$tools"
openssl_src="$tools/openssl-3.0.16"
plist_src="$tools/libplist-2.6.0"

# OpenSSL: static libcrypto only (3.0 wants ANDROID_NDK_ROOT)
export ANDROID_NDK_HOME="$ndk"
export ANDROID_NDK_ROOT="$ndk"
tc="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin"
export PATH="$tc:$PATH"
cd "$openssl_src"
./Configure "$openssl_target" -D__ANDROID_API__=$api no-shared no-tests no-ui-console
make -j"$(sysctl -n hw.ncpu)" build_libs
mkdir -p "$out/openssl/lib/$abi" "$out/openssl/include" "$out/openssl/include-abi/$abi/openssl"
cp libcrypto.a "$out/openssl/lib/$abi/"
cp -r include/openssl "$out/openssl/include/"
mv "$out/openssl/include/openssl/configuration.h" "$out/openssl/include-abi/$abi/openssl/"

# libplist: static C library (libplist-2.0) via autotools (the 2.6.0 release
# tarball has no CMake build). C++ bindings are unused but harmless.
cd "$plist_src"
./configure --host=$host CC=$cc_prefix$api-clang CXX=$cc_prefix$api-clang++ \
  AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip \
  --disable-shared --enable-static --without-cython --without-tests \
  --prefix="$tools/libplist-install"
make -j"$(sysctl -n hw.ncpu)"
make install || true   # ranlib lookup can fail post-install; archive is already in place
"$tc/llvm-ranlib" "$tools/libplist-install/lib/libplist-2.0.a"
# Drop DWARF debug info: it embeds absolute local build paths and isn't needed to link.
"$tc/llvm-strip" --strip-debug "$tools/libplist-install/lib/libplist-2.0.a"
mkdir -p "$out/plist/lib/$abi" "$out/plist/include/plist"
cp "$tools/libplist-install/lib/libplist-2.0.a" "$out/plist/lib/$abi/"
cp "$plist_src/include/plist/plist.h" "$out/plist/include/plist/"
echo "deps for $abi installed to $out"
