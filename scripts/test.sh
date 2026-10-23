#!/usr/bin/env bash
# Alpha quick test on Linux (Xvfb + Mesa is enough): builds, runs the game with -Dalphapt.test=true, prints the report.
# Needs JAVA_HOME pointing at Java 25 for Gradle. On Windows: add -Dalphapt.test=true to the AlphaPT instance's JVM arguments.
set -e
cd "$(dirname "$0")/.."
export DISPLAY=${DISPLAY:-:99}
pgrep -f "Xvfb $DISPLAY" >/dev/null || { Xvfb $DISPLAY -screen 0 1280x720x24 >/dev/null 2>&1 & sleep 2; }
rm -rf run/alphapt-test run/saves/alphapt-test run/config/alphapt-gui.properties
timeout ${TIMEOUT:-1500} gradle runClient -Ptest --console=plain > run-test.log 2>&1 || true
cat run/alphapt-test/report.txt 2>/dev/null || { echo "no report: see run-test.log"; grep -E "Exception|Caused" run-test.log | head; exit 1; }
grep -q "failed 0" run/alphapt-test/report.txt
