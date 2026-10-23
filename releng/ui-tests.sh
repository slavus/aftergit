#!/usr/bin/env bash
# Runs the UI test fragment on a virtual X display so nothing appears on the real screen.
set -euo pipefail
cd "$(dirname "$0")/.."
DISPLAY_NO="${AFTERGIT_XVFB_DISPLAY:-97}"
Xvfb ":$DISPLAY_NO" -screen 0 1600x1200x24 >/dev/null 2>&1 &
XVFB_PID=$!
trap 'kill "$XVFB_PID" 2>/dev/null || true' EXIT
sleep 1
if ! kill -0 "$XVFB_PID" 2>/dev/null; then
  echo "Xvfb failed to start on :$DISPLAY_NO (display busy or stale /tmp/.X$DISPLAY_NO-lock?)" >&2
  exit 1
fi
# GTK prefers Wayland when a session offers it: keep the test windows on the virtual X display
unset WAYLAND_DISPLAY
DISPLAY=":$DISPLAY_NO" GDK_BACKEND=x11 mvn -B -Pui-tests "$@" clean verify
