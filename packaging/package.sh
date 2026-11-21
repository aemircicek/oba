#!/usr/bin/env bash
# Builds Oba's installers for the system it runs on, into dist/:
#   Windows: installer (.exe, per-user, shortcuts, .scbackup association) and a portable .zip
#   macOS:   .dmg
#   Linux:   .deb
# plus the runnable jar. Used by the release workflow, and by CI on every change (with a test
# version, nothing uploaded) so packaging never breaks unnoticed.
#
#   packaging/package.sh <version> <platform>      e.g. 3.6.0 macos-arm64
#
# Expects target/secure-chat.jar (mvn package) and, on macOS, packaging/macos/oba.icns.
#
# Faster starts (AppCDS): the runtime gets the JDK's base class archive, and the app a second
# archive of its own classes, written by a training run of the packaged app (cds-image.sh). On
# Windows the JVM writes that second archive itself on the first start instead (the app folder
# belongs to the user there).
set -euo pipefail
VERSION="$1"                       # file names keep a suffix such as -rc.1; installers need plain numbers
PLATFORM="$2"
NUMBERS="${VERSION%%-*}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WIN_UPGRADE_UUID="${WIN_UPGRADE_UUID:-3b9f6c1e-6a52-4f0e-9a51-6c0f2c7e5a11}"   # never change: lets new versions replace old ones

rm -rf dist input pkg portable img
mkdir -p dist input
cp target/secure-chat.jar input/
cp target/secure-chat.jar "dist/Oba-${VERSION}-${PLATFORM}.jar"

# The base class archive needs the runtime's java command while jlink runs (so no --strip-native-commands).
JLINK=(--jlink-options "--strip-debug --no-man-pages --no-header-files --generate-cds-archive")
APP=(--name Oba --app-version "$NUMBERS" --vendor "Emir Cicek" --copyright "(c) 2026 Emir Cicek, MIT License"
     --description "End-to-end encrypted messenger")
FROM_JAR=(--input input --main-jar secure-chat.jar --main-class com.emirchat.Main "${JLINK[@]}")
ABOUT=(--about-url "https://github.com/aemircicek/oba")
ASSOC=(--file-associations packaging/scbackup.properties)

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*|Windows*)
    CDS=(--java-options '-XX:+AutoCreateSharedArchive' --java-options '-XX:SharedArchiveFile=$APPDIR/oba.jsa')
    jpackage "${APP[@]}" "${FROM_JAR[@]}" "${CDS[@]}" "${ABOUT[@]}" "${ASSOC[@]}" --type exe --icon packaging/windows/oba.ico --dest pkg \
      --win-per-user-install --win-menu --win-menu-group Oba --win-shortcut --win-shortcut-prompt \
      --win-dir-chooser --win-upgrade-uuid "$WIN_UPGRADE_UUID"
    cp pkg/*.exe "dist/Oba-${VERSION}-${PLATFORM}.exe"
    jpackage "${APP[@]}" "${FROM_JAR[@]}" "${CDS[@]}" --type app-image --icon packaging/windows/oba.ico --dest portable
    ZIP="dist/Oba-${VERSION}-${PLATFORM}-portable.zip"
    if command -v 7z > /dev/null; then (cd portable && 7z a -tzip "../$ZIP" Oba > /dev/null)
    else powershell -NoProfile -Command "Compress-Archive -Path portable/Oba -DestinationPath '$ZIP'"; fi ;;
  Darwin)
    RES=()
    if [ -f packaging/macos/resources/Info.plist ]; then RES=(--resource-dir packaging/macos/resources); fi
    MAC=(--mac-package-identifier org.oba.app --mac-package-name Oba)
    jpackage "${APP[@]}" "${FROM_JAR[@]}" ${RES[@]+"${RES[@]}"} "${MAC[@]}" "${ASSOC[@]}" --type app-image \
      --icon packaging/macos/oba.icns --dest img
    bash "$HERE/cds-image.sh" img/Oba.app img/Oba.app/Contents/MacOS/Oba img/Oba.app/Contents/app/Oba.cfg
    codesign --force --deep -s - img/Oba.app          # the bundle changed: seal it again (ad hoc, as jpackage did)
    codesign --verify --deep --strict img/Oba.app
    jpackage --type dmg --app-image img/Oba.app "${APP[@]}" "${ABOUT[@]}" "${MAC[@]}" --dest pkg
    cp pkg/*.dmg "dist/Oba-${VERSION}-${PLATFORM}.dmg" ;;
  Linux)
    jpackage "${APP[@]}" "${FROM_JAR[@]}" --type app-image --icon packaging/linux/oba.png --dest img
    bash "$HERE/cds-image.sh" img/Oba img/Oba/bin/Oba img/Oba/lib/app/Oba.cfg
    jpackage --type deb --app-image img/Oba "${APP[@]}" "${ABOUT[@]}" "${ASSOC[@]}" --icon packaging/linux/oba.png --dest pkg \
      --linux-package-name oba --linux-shortcut --linux-menu-group Network --linux-app-category net
    cp pkg/*.deb "dist/Oba-${VERSION}-${PLATFORM}.deb" ;;
  *)
    echo "Unknown system $(uname -s)" >&2
    exit 1 ;;
esac
ls -l dist
