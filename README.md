# ccez-clock

Alarm, stopwatch and timer for the owner's S24, replacing Samsung Clock
(see `PLAN.md`). 1.2 MB, our own sounds, AMOLED black.

- Agents anywhere on the tailnet: `bun scripts/clock.ts list|add|edit|delete|enable|disable|timer|phone`
  (the clock server in `server/`, on z; the phone syncs with it).
- Over adb: `bun scripts/clock.ts setup|status|get|set|log|backup|restore|ring|dismiss`.
- Build and release locally (private repo, no Actions): `scripts/build.sh`,
  `scripts/release.sh`. Gates on an emulator: `scripts/check.sh`,
  `scripts/roundtrip.sh`. Server: `scripts/server-install.sh`.
