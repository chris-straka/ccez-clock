# ccez-clock

Owner rules: `~/AGENTS.md`, `~/SWE/AGENTS.md`. The spec is `PLAN.md`;
keep its Status section current.

- Android only: Kotlin + Jetpack Compose, versions as ccez-launcher.
- Verify on the emulator (`clock_avd`, `adb -s emulator-5564`) with
  screenshots; never the owner's phone unless asked. Tests stay silent
  (emulator with `-no-audio`; nothing plays sound on the Mac).
- Setting an alarm for the owner: `bun scripts/clock.ts add 07:30 weekdays
  --label Gym` with `CLOCK_SERIAL` set to their phone, only when they ask.
- Commit small, push to `origin main`. Releases are local
  (`scripts/release.sh`), no GitHub Actions on this private repo.
