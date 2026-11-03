#!/usr/bin/env bash
# Builds the signed release APK as build/release/Lenshift.apk and prints its version and SHA-256.
#
#   tools/build_release.sh /path/to/key-info.txt [extra Gradle arguments]
#
# The key info file holds path=, storepass=, alias= and keypass= lines. Keep it, and the
# keystore it points to, outside the repository and backed up: every update must be signed
# with the same key, or Android refuses to install it over the previous version.
set -e
: "${1:?usage: tools/build_release.sh /path/to/key-info.txt [gradle args]}"
INFO="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
# Git Bash on Windows: hand the native Python a Windows path.
if command -v cygpath > /dev/null; then INFO="$(cygpath -w "$INFO")"; fi
shift
cd "$(dirname "$0")/.."

eval "$(python -I -c "
import shlex, sys
d = dict(l.strip().split('=', 1) for l in open(sys.argv[1]) if '=' in l)
for k, v in (('LENSHIFT_KEYSTORE', 'path'), ('LENSHIFT_KEYSTORE_PASSWORD', 'storepass'),
             ('LENSHIFT_KEY_ALIAS', 'alias'), ('LENSHIFT_KEY_PASSWORD', 'keypass')):
    print('export %s=%s' % (k, shlex.quote(d[v])))
" "$INFO")"

./gradlew -q :app:assembleRelease "$@"

# A fixed file name keeps the README's "latest release" download link working.
VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
mkdir -p build/release
OUT="build/release/Lenshift.apk"
cp app/build/outputs/apk/release/app-release.apk "$OUT"
echo "Lenshift $VERSION"
sha256sum "$OUT"
