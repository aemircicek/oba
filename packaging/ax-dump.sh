#!/usr/bin/env bash
# Prints what VoiceOver sees in a running Oba window: the macOS accessibility tree (role, name,
# description of every element). Needs the terminal allowed under System Settings > Privacy &
# Security > Accessibility.
#
#   packaging/ax-dump.sh [pid]       (default: the newest Oba or java process with a window)
set -euo pipefail
PID="${1:-$(pgrep -n -f 'secure-chat|Oba' || true)}"
[ -n "$PID" ] || { echo "no Oba process found" >&2; exit 1; }
osascript <<APPLESCRIPT
tell application "System Events"
    tell (first process whose unix id is $PID)
        set out to ""
        repeat with e in (entire contents of window 1)
            try
                set r to role of e
                set n to ""
                try
                    set n to name of e
                end try
                set d to ""
                try
                    set d to description of e
                end try
                if r is in {"AXButton", "AXTextField", "AXTextArea", "AXCheckBox", "AXRadioButton", "AXPopUpButton", "AXStaticText", "AXImage", "AXLink", "AXList", "AXRow"} then
                    set out to out & r & tab & n & tab & d & linefeed
                end if
            end try
        end repeat
        return out
    end tell
end tell
APPLESCRIPT
