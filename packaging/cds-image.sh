#!/usr/bin/env bash
# Adds a class-data-sharing archive to a jpackage app image, for faster starts (AppCDS).
#
#   packaging/cds-image.sh <app image> <launcher> <cfg file>
#
# Runs the packaged app once in training mode (--cds-train: it loads its classes and exits), which
# writes oba.jsa next to the app, and points the launcher's configuration at it. The archive is
# only read afterwards, so it suits app folders users cannot write to (a signed macOS bundle,
# /opt on Linux). If anything fails the image is left without it: the app works the same, only
# starts a little slower.
set -euo pipefail
IMAGE="$1"
LAUNCHER="$2"
CFG="$3"
APPDIR="$(dirname "$CFG")"
ARCHIVE="$APPDIR/oba.jsa"

if ! JAVA_TOOL_OPTIONS="-XX:ArchiveClassesAtExit=$ARCHIVE" \
        "$LAUNCHER" --cds-train > cds-train.log 2>&1 || [ ! -s "$ARCHIVE" ]; then
    echo "::warning::No AppCDS archive (the training run failed); see below"
    cat cds-train.log || true
    rm -f "$ARCHIVE"
    exit 0
fi
ls -l "$ARCHIVE"

# The launcher expands $APPDIR itself; -Xshare:auto ignores an archive that does not fit.
python3 - "$CFG" <<'EOF'
import sys
path = sys.argv[1]
lines = open(path).read().splitlines()
add = ["java-options=-XX:SharedArchiveFile=$APPDIR/oba.jsa", "java-options=-Xshare:auto", "java-options=-Xlog:cds=off"]
if "[JavaOptions]" in lines:
    i = lines.index("[JavaOptions]") + 1
    lines[i:i] = add
else:
    lines += ["", "[JavaOptions]"] + add
open(path, "w").write("\n".join(lines) + "\n")
EOF
cat "$CFG"
