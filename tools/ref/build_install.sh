#!/usr/bin/env bash
# Builds the debug APK signed with the release key (so installs keep app data) and installs it.
set -e
cd "$(dirname "$0")/../.."
export GRADLE_USER_HOME="$(cygpath -w "$PWD/.gradle-home")" MSYS_NO_PATHCONV=1
eval "$(python -I -c "
import shlex
d=dict(l.strip().split('=',1) for l in open('KEYSTORE_INFO.txt') if '=' in l)
for k,v in (('LENSHIFT_KEYSTORE','path'),('LENSHIFT_KEYSTORE_PASSWORD','storepass'),('LENSHIFT_KEY_ALIAS','alias'),('LENSHIFT_KEY_PASSWORD','keypass')):
    print('export %s=%s' % (k, shlex.quote(d[v])))
")"
G=.gradle-home/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle
if ! $G -q :app:assembleDebug "$@" > build/last_build.log 2>&1; then
  grep -v "^w: " build/last_build.log | head -40; echo "BUILD FAILED"; exit 1
fi
APK=app/build/outputs/apk/debug/app-debug.apk
adb install -r "$APK"
P=$(adb shell pm path dev.lenshift.app | head -1 | sed 's/package://' | tr -d '\r')
[ "$(adb shell md5sum "$P" | cut -d' ' -f1)" = "$(md5sum "$APK" | cut -d' ' -f1)" ] && echo "INSTALL VERIFIED" || echo "INSTALL MISMATCH"
