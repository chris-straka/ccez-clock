# ccez-clock

A small clock app for the owner's Galaxy S24: alarm, stopwatch and timer,
nothing else. It replaces Samsung Clock. Owner, 2026-11-12: "make a clone
of the Samsung one but make it take up less space… I don't need 500 alarm
noises, just some pleasant ones… agent a11y and stuff."

Read `~/AGENTS.md` (owner rules) and `~/SWE/AGENTS.md` first. This plan is
the spec; update it as you learn.

## Ground rules

- Kotlin + Jetpack Compose, minSdk 26, the same Gradle, AGP and library
  versions as `~/SWE/ccez-launcher` (its `gradle/libs.versions.toml` and
  wrapper, trimmed to what this app uses).
- Samsung Clock's structure and ease (three tabs at the bottom: Alarm,
  Stopwatch, Timer), our own design and code: no Samsung assets, icons,
  sounds or code. World clock and bedtime are out.
- Quiet UI: pure black (AMOLED), no branding, no helper text, sentence-case
  labels of a few words, large touch targets (48 dp at least, the main
  buttons far larger).
- Small: R8 and resource shrinking on, no icon libraries (icons are a few
  hand-drawn paths), sounds synthesized on the phone, so the APK carries
  none. Measure the APK at every release (Status).
- Verify on the emulator only (`clock_avd`, port 5564, `-no-window
  -no-audio`; `adb -s emulator-5564`). Never the owner's phone unless the
  owner asks that turn. Tests stay silent.
- Releases: public repo `chris-straka/ccez-clock`, signed APK per release
  from `scripts/release.sh` (key in `~/.config/ccez-clock/`, made once by
  `scripts/build.sh --new-key`; build.sh stops without it, and release.sh
  refuses an APK whose certificate differs from the last release's).
  Self-update from GitHub releases (no token: the repo is public), on
  unless `updates.self` is turned off. No GitHub Actions.

## Alarms that always ring

- `AlarmManager.setAlarmClock` for every alarm and snooze (the status-bar
  icon and lock-screen "next alarm" come with it; Doze never delays it).
  `USE_EXACT_ALARM` (a clock app qualifies), with `SCHEDULE_EXACT_ALARM`
  for Android 12.
- On fire: a broadcast receiver takes a wake lock and starts the ring
  service (foreground, `systemExempted`, which exact-alarm apps may use for
  alarms). It posts an alarm notification with a full-screen intent to
  the ring screen (over the lock screen, screen turned on), plays the
  sound on the alarm stream (`USAGE_ALARM`, so Do not disturb lets it
  through by default) with a fade-in, vibrates when the alarm says so, and
  goes quiet after `alarm.ring_minutes`.
- Unanswered, an alarm rings again a snooze later, `alarm.ring_again`
  times (3) in a row, as Samsung Clock does; only then it counts as
  dismissed (a one-off turns off). A ring that never ends (the process
  killed mid-ring) rings again the same way: the retry is saved when the
  ring starts. Two alarms in the same minute ring as one, both labels
  shown, one answer for both; an alarm cut short by the timer rings again.
  A "Snoozed until" line in the shade shows each snooze or re-ring still
  ahead and follows the alarms (Scheduler), so it goes when the snooze
  rings, the re-rings run out, or the alarm is dismissed or moved; its
  Dismiss ends only the snooze it names.
- Opening the app while something rings brings the ring screen back. The
  Alarm tab shows a line for any phone check that's off (below), a tap
  from its fix.
- A muted alarm stream is raised to `alarm.min_volume` percent while
  ringing and put back after (0 turns this off).
- Rescheduled on boot (also before the first unlock: data lives in
  device-protected storage and the app is direct-boot aware, since the
  S24 restarts itself at night by default), time and time-zone changes,
  app updates and the exact-alarm permission coming back.
- Next ring: the next matching day at the alarm's local time. Daylight
  saving: a time skipped in spring rings at the same wall time shifted
  past the gap (02:30 → 03:30); a repeated time in autumn rings once, at
  the first. Snooze is now plus the alarm's snooze length. Moving an
  alarm (time or days) ends its snooze.

### What the phone needs (Galaxy S24, One UI 8)

`bun scripts/clock.ts setup` does the first four over adb and reports
the rest:

1. Battery optimization off (`cmd deviceidle whitelist +uk.ccez.clock`):
   Settings › Apps › ccez-clock › Battery › Unrestricted.
2. Background allowed (`appops set … RUN_ANY_IN_BACKGROUND allow`).
3. Notifications allowed (`pm grant … POST_NOTIFICATIONS`).
4. Full-screen alarms (Android 14+, `appops set … USE_FULL_SCREEN_INTENT
   allow`): Settings › Apps › ccez-clock › Full screen notifications.
5. Samsung's sleeping apps, by hand: Settings › Battery › Background
   usage limits › Never auto sleeping apps › add ccez-clock (and make sure it
   isn't under Sleeping or Deep sleeping apps). Unrestricted battery
   usually keeps it out, but One UI can still put it to sleep.
6. Alarms keep the alarm volume: the side keys change media volume by
   default; the ring raises a muted alarm stream to `alarm.min_volume`.

`clock.ts status` shows each of these as the phone sees it.

## Timer and stopwatch

- One timer: presets (`timer.presets`, minutes) and a keypad, pause,
  +1 min, cancel. Runs from timestamps; an exact alarm on the
  elapsed-time clock (`setExactAndAllowWhileIdle`) rings it like an alarm.
  An ongoing notification counts down (system chronometer, no service).
- Stopwatch: start, lap, stop, reset. Timestamps only (elapsed-time clock,
  wall clock after a reboot); nothing ticks while the app is closed.

## Sounds

Six sounds, made by this app's own code (`Synth.kt`), rendered into memory
when they play: nothing to license, nothing in the APK. Provenance:

| id        | what it is                                         |
| --------- | -------------------------------------------------- |
| `chimes`  | three soft bell strikes (inharmonic partials)      |
| `marimba` | a rising pentatonic marimba run                    |
| `dawn`    | a slow warm chord swell (detuned sines)            |
| `kalimba` | a plucked-tine phrase                              |
| `musicbox`| a high music-box figure                            |
| `pulse`   | soft two-tone pulses, for waking from deep sleep   |

All synthesized from sine partials with envelopes, written in 2026-11 for
this repo (MIT-style, ours). `scripts/sounds.sh` renders them to WAV for
listening.

## Agents (owner, 2026-11-12: any tailnet computer, not adb)

An agent on any computer on the tailnet reads and sets the alarms and the
timer through the clock server; the phone syncs with it. adb stays for
checks and the phone's own settings.

- Server: `server/` (Rust, axum + SQLite), on z beside ccez-words but its
  own process: words is live with its own dictionaries and releases, and
  neither should restart for the other. 127.0.0.1:8799, on the tailnet
  only at `https://<z's tailnet name>:8450` (`tailscale serve`, never
  Funnel), launchd `uk.ccez.clock`, database `~/.ccez-clock/clock.sqlite`.
  Install or update: `scripts/server-install.sh`. A bearer token is
  checked when `CCEZ_CLOCK_TOKEN` is set (not needed while tailnet-only;
  the phone takes one with `clock.ts token <t>`).
- Routes: `GET /alarms?since=`, `POST /alarms` (one or a list),
  `PATCH /alarms/{id}`, `DELETE /alarms/{id}` (a tombstone, so the delete
  travels), `GET|POST /timer`, `GET /phone` (each phone's last report:
  version, next ring, ringing, health, settings, UTC offset), `POST /sync`
  (the phone's one call). Per alarm the later change wins by `at`, never
  later than its arrival, and a losing change gets the winner sent back
  (ccez-words' rule). The phone takes the server's copy of any alarm it
  owes nothing for (the server stamps times by its own clock, so a phone
  clock running ahead can't keep an agent's change out). The sync answer
  names the server's database; when that changes (the file made anew)
  the phone starts its cursor over and sends every alarm again; in that
  answer the server's copy wins only where it's newer (the new file may
  be an older copy).
- CLI: `bun scripts/clock.ts list|add|edit|delete|enable|disable|timer|phone`
  over HTTP (server from `CLOCK_SERVER`, `~/.config/ccez-clock/server`, or
  z's address). `add 07:30 weekdays --label Gym`, `add +20`, `timer 25m`.
  "+N" is worked out on the phone's wall clock from the UTC offset it
  reports (time-zone rules differ between machines: in 2026-11 the Mac's
  Edmonton was UTC-6 and the emulator's UTC-7). Over adb, `--adb` and
  `status|schema|dump|get|set|log|sync|next|ring|dismiss|snooze|backup|
  restore|setup`.
- Phone sync: on opening the app; 1.5 s after any change on the phone;
  a JobScheduler job every 15 minutes with a network (no Play services);
  and an exact wake-up allowed through Doze at the earlier of 3 minutes
  before the next ring and `sync.minutes` (30) from now.
- Worst-case delay for an agent's change to reach the phone: about 15
  minutes while the phone is awake or in light Doze, 30 minutes
  (`sync.minutes`) in deep Doze — exact allow-while-idle wake-ups fire
  there and a battery-exempt app keeps its network. A moved or
  cancelled alarm is also checked 3 minutes before it rings. Longer only
  when the phone is offline or off the tailnet (it retries at each
  wake-up), or when One UI puts the app to sleep despite the setup below.
  An agent's alarm for less than that delay away may ring late or not at
  all; a timer an agent starts runs from when the agent started it.

The log is JSON lines with context (which alarm, when it was due, how
late it fired, how it ended, each sync). Backup is settings plus alarms
as one JSON, checked whole before writing.

## Accessibility

TalkBack labels on every control (an alarm row reads "7:30 AM, weekdays,
Gym, on"), toggles and day chips announce their state, the ring screen
announces the time and label and has two big buttons, everything scrolls
at the largest font and display size.

## Quality gates (`scripts/check.sh`, emulator only)

Server tests (`cargo test`), unit tests (scheduling math: next ring,
repeat days, DST, snooze; timer, stopwatch, the synth, settings), Compose
UI tests of every tab, and instrumented tests that an alarm set a minute
ahead brings up the ring screen over the off screen, snooze, dismiss and
the timer ring. Screenshots of each tab and the ring screen at normal and
largest font/display size in `screenshots/` (`scripts/shots.sh`).
`scripts/roundtrip.sh` checks the agent path against the real server.

Emulator notes: `clock_avd` (copied from launcher_avd's config, port
5564). Its DNS proxy flips between MagicDNS and public answers for the
tailnet name, so the app on the emulator syncs with `http://10.0.2.2:8799`
(the same server, through the host); the CLI uses the tailnet address.
With `-no-audio` the emulator can't create an AudioTrack, and its media
stack stalls system processes into ANR dialogs (`hide_error_dialogs 1`
in the scripts). That found a real bug: a failed sound used to crash the
ring; now it falls back to the phone's alarm tone, then to screen and
vibration alone.

## Not doing

World clock, bedtime, multiple timers, alarm sound downloads or custom
ringtones, Samsung's look.

## Status

2026-11-14: released v0.1.35 (APK 1,244 KB), fixing a review of v0.1.29
(also 1,244 KB, released untested on a device): "Snoozed until" lines
never went away (now they follow the alarms, and a stale Dismiss can't
end a newer snooze); a server database replaced by an older copy could
overwrite newer alarms on the phone (now only newer server copies win in
that answer); settings files from v0.1.23 and v0.1.29 kept every old
default as if chosen (now those are dropped once, file format 3, so
later default changes reach the phone; sync.server is always kept);
release.sh found the SDK only after its test gate, and a fresh clone
stopped it (checked in a fresh clone: the gate failed before, passes
now). Verified by unit tests (37) and server tests (8). Not verified:
the ring path changed in v0.1.29 and this release (re-rings, joined
rings, the ring screen reopening, shade lines, glyph buttons) has not
run on an emulator or the S24 (no emulator for this pass): run
`scripts/check.sh` on clock_avd before trusting it, and walk a missed
alarm's re-rings there. The phone still has v0.1.23 (self-update off),
so it needs v0.1.35 installed once by hand.

2026-11-14: fixed the review of v0.1.23 (the version on the owner's
phone). An unanswered alarm now rings again (3 times, a snooze apart)
instead of counting as dismissed after 10 minutes; a ring killed midway
rings again too; same-minute alarms ring together; opening the app while
ringing brings the ring screen back; phone checks that are off show on
the Alarm tab; moving a snoozed alarm ends the snooze; the server's copy
wins when the phone owes nothing (clock skew); a recreated server
database resets the phone's cursor; a late timer rings through an exact
wake-up; the stopwatch, timer and editor actions are glyphs with spoken
names; day chips are 56 dp tall; `updates.self` is on by default (an old
settings file's stored default is dropped once). build.sh makes a key
only with `--new-key`; release.sh checks the certificate against the
last release. Verified by unit tests (33) and server tests (8);
instrumented tests compile but weren't run (no emulator for this pass),
and nothing was checked on the S24. v0.1.23 has self-update off, so the
phone needs this release installed once by hand; after that it updates
itself.

2026-11-12: released v0.1.17 (APK 1,244 KB: R8, en/fr resources only,
no icon library, sounds synthesized). Verified on clock_avd with
`scripts/check.sh` (6 server tests, 20 unit tests, 9 instrumented tests:
an alarm a minute ahead rang over the off screen 36-59 ms late, snooze,
dismiss, timer ring; screenshots) and `scripts/roundtrip.sh` (an alarm
made on the phone showed in `clock list`; one added from the Mac rang
over the off screen after the periodic sync job; its dismiss showed in
`clock list`). Also by hand: an agent's alarm arriving in forced deep
Doze through the sync wake-up; alarms rescheduled on locked boot and
boot. The server's test data was cleared after (backup in
`~/.ccez-clock/`).
Not verified: anything on the S24 (One UI's sleeping apps, the real
alarm sound and its fade, vibration); the sounds have been checked by
numbers only (level, length, clean loop): they need a listen
(`scripts/sounds.sh`).
