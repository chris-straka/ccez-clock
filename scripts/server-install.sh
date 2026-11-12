#!/usr/bin/env bash
# Builds the clock server and runs it on this Mac (z) under launchd,
# on 127.0.0.1:8799, put on the tailnet (only) at https://<host>:8450 by
# tailscale serve. Run again to update: it rebuilds and restarts.
#   scripts/server-install.sh
set -euo pipefail
cd "$(dirname "$0")/../server"
PORT="${CCEZ_CLOCK_PORT:-8799}"
HTTPS="${CCEZ_CLOCK_HTTPS:-8450}"
cargo build --release -q
mkdir -p "$HOME/.local/bin" "$HOME/.ccez-clock"
cp target/release/ccez-clock-server "$HOME/.local/bin/ccez-clock-server"
PLIST="$HOME/Library/LaunchAgents/uk.ccez.clock.plist"
cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>uk.ccez.clock</string>
	<key>ProgramArguments</key>
	<array>
		<string>$HOME/.local/bin/ccez-clock-server</string>
	</array>
	<key>EnvironmentVariables</key>
	<dict>
		<key>CCEZ_CLOCK_PORT</key>
		<string>$PORT</string>
	</dict>
	<key>KeepAlive</key>
	<true/>
	<key>RunAtLoad</key>
	<true/>
	<key>StandardErrorPath</key>
	<string>$HOME/.ccez-clock/server.log</string>
</dict>
</plist>
PL
if launchctl print "gui/$(id -u)/uk.ccez.clock" >/dev/null 2>&1; then
  launchctl kickstart -k "gui/$(id -u)/uk.ccez.clock"
else
  launchctl bootstrap "gui/$(id -u)" "$PLIST"
fi
# Tailnet only: serve, never funnel.
tailscale serve --bg --https="$HTTPS" "http://127.0.0.1:$PORT" >/dev/null
for _ in 1 2 3 4 5 6 7 8 9 10; do curl -sf "http://127.0.0.1:$PORT/health" >/dev/null && break; sleep 0.5; done
curl -sf "http://127.0.0.1:$PORT/health" >/dev/null || { echo "server didn't come up: $HOME/.ccez-clock/server.log" >&2; exit 1; }
HOST="$(tailscale status --self --json | python3 -c 'import json,sys;print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')"
echo "clock server up: https://$HOST:$HTTPS (tailnet only)"
