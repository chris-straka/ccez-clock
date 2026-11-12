#!/usr/bin/env bun
// The owner's alarms and timer, from any computer on the tailnet.
//
// Alarms and the timer go through the clock server (server/, on z); the
// phone picks changes up on its next sync (PLAN.md: "Agents"):
//   bun scripts/clock.ts list [--json]
//   bun scripts/clock.ts add <HH:MM|+min> [days] [--label X] [--sound S] [--no-vibrate] [--snooze N] [--off]
//   bun scripts/clock.ts edit <id> [HH:MM|+min] [days] [--label X] ...
//   bun scripts/clock.ts delete|enable|disable <id>
//   bun scripts/clock.ts timer [5m|1h30m|90 | pause | resume | cancel | add]
//   bun scripts/clock.ts phone             what the phone last reported
// days: once, daily, weekdays, weekends, mon,wed,fri, mon-fri
// Server: CLOCK_SERVER or ~/.config/ccez-clock/server (z's tailnet name, port 8450).
//
// Over adb, for checks and the phone's own settings (CLOCK_SERIAL, or the
// only device connected; --adb sends the alarm commands this way too):
//   status|schema|dump|get [key]|set <key> <value>|log [n]|sync|next
//   ring <id|timer>|dismiss|snooze|ringing|backup [file]|restore [file]
//   setup       grants what adb can for alarms that always ring
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";

const URI = "content://uk.ccez.clock.control";
const PKG = "uk.ccez.clock";
const CONFIG = `${homedir()}/.config/ccez-clock`;

// ---------- the server

function server(): string {
  const env = process.env.CLOCK_SERVER;
  if (env) return env.replace(/\/$/, "");
  const f = `${CONFIG}/server`;
  if (existsSync(f)) return readFileSync(f, "utf8").trim().replace(/\/$/, "");
  throw new Error(`no clock server: set CLOCK_SERVER or write its URL to ${f} (z's tailnet name, port 8450)`);
}

function token(): string | undefined {
  if (process.env.CLOCK_TOKEN) return process.env.CLOCK_TOKEN;
  const f = `${CONFIG}/token`;
  return existsSync(f) ? readFileSync(f, "utf8").trim() : undefined;
}

async function http(method: string, path: string, body?: unknown): Promise<any> {
  const headers: Record<string, string> = { "content-type": "application/json" };
  const t = token();
  if (t) headers.authorization = `Bearer ${t}`;
  let res: Response;
  try {
    res = await fetch(server() + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) });
  } catch (e) {
    throw new Error(`can't reach the clock server at ${server()} (on the tailnet?): ${e instanceof Error ? e.message : e}`);
  }
  const text = await res.text();
  let data: any;
  try {
    data = JSON.parse(text);
  } catch {
    data = text;
  }
  if (!res.ok) throw new Error(data?.error ?? `${res.status} ${text}`);
  return data;
}

// ---------- adb

function serial(): string {
  const env = process.env.CLOCK_SERIAL;
  if (env) return env;
  const out = spawnSync("adb", ["devices"], { encoding: "utf8" });
  const devices = (out.stdout ?? "").split("\n").slice(1).filter((l) => l.endsWith("\tdevice")).map((l) => l.split("\t")[0]);
  if (devices.length === 1) return devices[0];
  if (devices.length === 0) throw new Error("no device: plug it in or start an emulator");
  throw new Error(`several devices (${devices.join(", ")}); set CLOCK_SERIAL`);
}

const b64 = (s: string) => Buffer.from(s, "utf8").toString("base64");
const unb64 = (s: string) => Buffer.from(s, "base64").toString("utf8");
const quote = (s: string) => `'${s.replaceAll("'", "'\\''")}'`;

function adb(...args: string[]) {
  return spawnSync("adb", ["-s", serial(), ...args], { encoding: "utf8" });
}

function call(method: string, arg?: string, value?: string): string {
  let cmd = `content call --uri ${URI} --method ${method}`;
  if (arg !== undefined) cmd += ` --arg ${quote(arg)}`;
  if (value !== undefined) cmd += ` --extra value:s:${quote(b64(value))}`;
  const out = adb("shell", cmd);
  const text = (out.stdout ?? "") + (out.stderr ?? "");
  if (text.includes("Could not find provider") || text.includes("Unknown authority")) throw new Error("ccez-clock isn't installed on that device");
  const pick = (name: string): string | undefined => {
    const at = text.indexOf(`${name}=`);
    if (at < 0) return undefined;
    const rest = text.slice(at + name.length + 1);
    const end = rest.search(/[,}]/);
    return unb64(rest.slice(0, end < 0 ? rest.length : end).trim());
  };
  const err = pick("error");
  if (err !== undefined) throw new Error(err);
  const got = pick("out");
  if (got === undefined) throw new Error(`unexpected answer from the device: ${text.trim()}`);
  return got;
}

// ---------- alarms, as words

const DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];

function parseDays(s: string): string[] | undefined {
  const t = s.toLowerCase().replaceAll(" ", "");
  if (t === "once" || t === "none") return [];
  if (["daily", "everyday", "every-day", "all"].includes(t)) return [...DAYS];
  if (t === "weekdays") return DAYS.slice(0, 5);
  if (t === "weekends" || t === "weekend") return DAYS.slice(5);
  const out = new Set<string>();
  for (const part of t.split(",")) {
    const range = part.split("-");
    const idx = range.map((d) => DAYS.indexOf(d.slice(0, 3)));
    if (idx.some((i) => i < 0)) return undefined;
    if (idx.length === 1) out.add(DAYS[idx[0]]);
    else if (idx.length === 2) for (let i = idx[0]; ; i = (i + 1) % 7) { out.add(DAYS[i]); if (i === idx[1]) break; }
    else return undefined;
  }
  return DAYS.filter((d) => out.has(d));
}

/**
 * "+N" minutes from now on the phone's wall clock: its UTC offset as it
 * last reported it (time-zone rules differ between machines: the emulator's
 * Edmonton was an hour off the Mac's in 2026-11), else this computer's.
 */
let phoneOffsetMin: number | undefined;

function resolveTime(s: string): string {
  const rel = /^\+(\d{1,4})$/.exec(s);
  if (rel) {
    let t = Date.now() + Number(rel[1]) * 60000;
    if (t % 60000) t += 60000 - (t % 60000);
    if (phoneOffsetMin === undefined) {
      const d = new Date(t);
      return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
    }
    const d = new Date(t + phoneOffsetMin * 60000);
    return `${String(d.getUTCHours()).padStart(2, "0")}:${String(d.getUTCMinutes()).padStart(2, "0")}`;
  }
  const m = /^(\d{1,2}):(\d{2})$/.exec(s);
  if (!m || Number(m[1]) > 23 || Number(m[2]) > 59) throw new Error(`time is HH:MM or +minutes, not "${s}"`);
  return `${m[1].padStart(2, "0")}:${m[2]}`;
}

const isTime = (s: string) => /^\+\d+$/.test(s) || /^\d{1,2}:\d{2}$/.test(s);

/** The fields an add or edit sets, from its words. */
function fields(words: string[], forAdb: boolean): Record<string, unknown> {
  const f: Record<string, unknown> = {};
  for (let i = 0; i < words.length; i++) {
    const w = words[i];
    const next = () => {
      const v = words[++i];
      if (v === undefined) throw new Error(`${w} needs a value`);
      return v;
    };
    if (w === "--label") f.label = next();
    else if (w === "--sound") f.sound = next();
    else if (w === "--snooze") f.snooze = Number(next());
    else if (w === "--no-vibrate") f.vibrate = false;
    else if (w === "--vibrate") f.vibrate = true;
    else if (w === "--off") f.enabled = false;
    else if (w === "--on") f.enabled = true;
    // The phone works out "+5" against its own clock; the server needs the time now.
    else if (isTime(w)) f.time = forAdb ? w : resolveTime(w);
    else {
      const d = parseDays(w);
      if (!d) throw new Error(`don't know "${w}" (a time, days like weekdays or mon,wed, or --label/--sound/--snooze/--no-vibrate/--off)`);
      f.days = d;
    }
  }
  return f;
}

function newId(): string {
  const a = "0123456789abcdefghijklmnopqrstuvwxyz";
  return Array.from(crypto.getRandomValues(new Uint8Array(10)), (b) => a[b % 36]).join("");
}

function daysText(days: string[]): string {
  if (!days?.length) return "once";
  if (days.length === 7) return "daily";
  if (days.join() === DAYS.slice(0, 5).join()) return "weekdays";
  if (days.join() === DAYS.slice(5).join()) return "weekends";
  return days.join(",");
}

function show(a: any): string {
  return [a.id, a.time, a.enabled ? "on " : "off", daysText(a.days).padEnd(9), a.label || ""].join("  ").trimEnd();
}

async function liveAlarms(): Promise<any[]> {
  const r = await http("GET", "/alarms");
  return (r.alarms as any[]).filter((a) => !a.deleted).sort((a, b) => a.time.localeCompare(b.time));
}

async function findId(prefix: string | undefined): Promise<string> {
  if (!prefix) throw new Error("which alarm? an id from `clock list`");
  const all = await liveAlarms();
  const exact = all.find((a) => a.id === prefix);
  if (exact) return exact.id;
  const hits = all.filter((a) => a.id.startsWith(prefix));
  if (hits.length === 1) return hits[0].id;
  throw new Error(hits.length ? `${prefix} matches ${hits.length} alarms` : `no alarm ${prefix}`);
}

function ago(ms: number): string {
  const s = Math.round((Date.now() - ms) / 1000);
  if (s < 90) return `${s} s ago`;
  if (s < 5400) return `${Math.round(s / 60)} min ago`;
  return `${Math.round(s / 3600)} h ago`;
}

// ---------- timer

function fmt(ms: number): string {
  const s = Math.ceil(Math.max(0, ms) / 1000);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return h ? `${h}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}` : `${m}:${String(sec).padStart(2, "0")}`;
}

function duration(s: string): number {
  if (/^\d+$/.test(s)) return Number(s) * 1000;
  const m = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/.exec(s);
  if (!m || !s) throw new Error("durations look like 90, 5m, 1h30m, 2m30s");
  const ms = ((Number(m[1] ?? 0) * 60 + Number(m[2] ?? 0)) * 60 + Number(m[3] ?? 0)) * 1000;
  if (ms <= 0) throw new Error("a duration is more than 0");
  return ms;
}

function timerText(t: any): string {
  if (t.state === "running") return `running, ${fmt(t.endsAt - Date.now())} left (rings ${new Date(t.endsAt).toLocaleTimeString()})`;
  if (t.state === "paused") return `paused, ${fmt(t.remaining)} left`;
  return "idle";
}

async function timerCmd(arg?: string): Promise<void> {
  const now = Date.now();
  const t = await http("GET", "/timer");
  let next: any;
  switch (arg) {
    case undefined:
    case "status":
      console.log(timerText(t));
      return;
    case "pause":
      if (t.state !== "running") throw new Error("the timer isn't running");
      next = { state: "paused", total: t.total, endsAt: 0, remaining: Math.max(0, t.endsAt - now), at: now };
      break;
    case "resume":
      if (t.state !== "paused") throw new Error("the timer isn't paused");
      next = { state: "running", total: t.total, endsAt: now + t.remaining, remaining: 0, at: now };
      break;
    case "cancel":
      next = { state: "idle", total: 0, endsAt: 0, remaining: 0, at: now };
      break;
    case "add":
      next = t.state === "running" ? { ...t, total: t.total + 60000, endsAt: Math.max(t.endsAt, now) + 60000, at: now }
        : t.state === "paused" ? { ...t, total: t.total + 60000, remaining: t.remaining + 60000, at: now }
        : { state: "running", total: 60000, endsAt: now + 60000, remaining: 0, at: now };
      break;
    default: {
      const ms = duration(arg);
      next = { state: "running", total: ms, endsAt: now + ms, remaining: 0, at: now };
    }
  }
  console.log(timerText(await http("POST", "/timer", next)));
}

// ---------- setup over adb

function setup(): void {
  const steps: [string, string[]][] = [
    ["notifications", ["shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS"]],
    ["battery optimization off", ["shell", "cmd", "deviceidle", "whitelist", `+${PKG}`]],
    ["background allowed", ["shell", "cmd", "appops", "set", PKG, "RUN_ANY_IN_BACKGROUND", "allow"]],
    ["full-screen alarms", ["shell", "cmd", "appops", "set", PKG, "USE_FULL_SCREEN_INTENT", "allow"]],
    ["exact alarms (Android 12)", ["shell", "cmd", "appops", "set", PKG, "SCHEDULE_EXACT_ALARM", "allow"]],
  ];
  for (const [name, args] of steps) {
    const r = adb(...args);
    const said = ((r.stdout ?? "") + (r.stderr ?? "")).trim().split("\n")[0];
    console.log(`${r.status === 0 ? "ok  " : "fail"}  ${name}${r.status === 0 || !said ? "" : `: ${said}`}`);
  }
  const s = JSON.parse(call("status"));
  console.log("\nphone checks:", Object.entries(s.health).map(([k, v]) => `${k}=${v}`).join(" "));
  console.log("by hand on a Samsung: Settings > Battery > Background usage limits > Never auto sleeping apps > add Clock");
}

// ---------- main

const HELP = readFileSync(import.meta.path, "utf8").split("\n").filter((l) => l.startsWith("//")).slice(0, 22).map((l) => l.slice(3)).join("\n");

async function loadPhoneZone(words: string[]): Promise<void> {
  if (!words.some((w) => /^\+\d+$/.test(w))) return;
  try {
    const r = await http("GET", "/phone");
    const off = r.phones?.[0]?.state?.offset_min;
    if (typeof off === "number") phoneOffsetMin = off;
  } catch {
    // No server: this computer's zone.
  }
}

async function main(): Promise<void> {
  let argv = process.argv.slice(2);
  const viaAdb = argv.includes("--adb");
  const asJson = argv.includes("--json");
  argv = argv.filter((a) => a !== "--adb" && a !== "--json");
  const [cmd, ...rest] = argv;
  switch (cmd) {
    case "list": {
      if (viaAdb) return console.log(call("list"));
      const all = await liveAlarms();
      if (asJson) return console.log(JSON.stringify(all, null, 2));
      console.log(all.length ? all.map(show).join("\n") : "no alarms");
      return;
    }
    case "add": {
      if (!viaAdb) await loadPhoneZone(rest);
      const f = fields(rest, viaAdb);
      if (!f.time) throw new Error("add needs a time: HH:MM or +minutes");
      if (viaAdb) return console.log(call("add", undefined, JSON.stringify(f)));
      const r = await http("POST", "/alarms", { id: newId(), days: [], enabled: true, ...f, at: Date.now() });
      console.log(show(r.alarms[0]));
      return;
    }
    case "edit": {
      const [id, ...words] = rest;
      if (!viaAdb) await loadPhoneZone(words);
      const f = fields(words, viaAdb);
      // A new time or new days turns it on, as on the phone (unless --off).
      if ((f.time || f.days) && f.enabled === undefined) f.enabled = true;
      if (viaAdb) return console.log(call("edit", id, JSON.stringify(f)));
      const r = await http("PATCH", `/alarms/${await findId(id)}`, f);
      console.log(show(r.alarms[0]));
      return;
    }
    case "enable":
    case "disable": {
      if (viaAdb) return console.log(call(cmd, rest[0]));
      const r = await http("PATCH", `/alarms/${await findId(rest[0])}`, { enabled: cmd === "enable" });
      console.log(show(r.alarms[0]));
      return;
    }
    case "delete": {
      if (viaAdb) return console.log(call("delete", rest[0]));
      const id = await findId(rest[0]);
      await http("DELETE", `/alarms/${id}`);
      console.log(`deleted ${id}`);
      return;
    }
    case "timer":
      if (viaAdb) return console.log(call("timer", rest.length ? rest[0] === "status" || ["pause", "resume", "cancel", "add"].includes(rest[0]) ? rest[0] : `start ${rest[0]}` : undefined));
      return timerCmd(rest[0]);
    case "phone": {
      const r = await http("GET", "/phone");
      if (asJson) return console.log(JSON.stringify(r, null, 2));
      if (!r.phones.length) return console.log("no phone has synced yet");
      for (const p of r.phones) {
        const s = p.state ?? {};
        const bad = Object.entries(s.health ?? {}).filter(([k, v]) => v === false && k !== "alarm_volume_pct").map(([k]) => k);
        console.log(`${p.device}: seen ${ago(p.seen)}, ${s.version ?? "?"}, next ${s.next ?? "none"}${s.ringing ? `, RINGING ${s.ringing.kind}` : ""}${bad.length ? `, needs: ${bad.join(" ")}` : ""}`);
      }
      return;
    }
    case "status": case "schema": case "dump": case "next": case "ringing": case "update":
      return console.log(call(cmd));
    case "dismiss": case "snooze": case "sync":
      return console.log(call(cmd));
    case "get": return console.log(call("get", rest[0]));
    case "set":
      if (rest.length < 2) throw new Error("set needs a setting and a value");
      return console.log(call("set", rest[0], rest.slice(1).join(" ")));
    case "log": return console.log(call("log", rest[0]));
    case "ring": return console.log(call("ring", rest[0]));
    case "token": return console.log(call("token", undefined, rest[0] ?? ""));
    case "setup": return setup();
    case "backup": {
      const out = spawnSync("adb", ["-s", serial(), "exec-out", "content", "read", "--uri", `${URI}/backup`]);
      if (out.status !== 0 || !out.stdout?.length) throw new Error("backup failed");
      if (rest[0]) { writeFileSync(rest[0], out.stdout); console.log(`wrote ${rest[0]}`); } else process.stdout.write(out.stdout);
      return;
    }
    case "restore": {
      const data = rest[0] ? readFileSync(rest[0]) : readFileSync(0);
      JSON.parse(data.toString("utf8"));
      const out = spawnSync("adb", ["-s", serial(), "shell", `content write --uri ${URI}/restore`], { input: data });
      if (out.status !== 0) throw new Error(`restore failed: ${out.stderr?.toString() ?? ""}`);
      console.log("sent; `clock log` says how it went");
      return;
    }
    case undefined: case "help": case "-h": case "--help":
      return console.log(HELP);
    default:
      console.error(`no command called ${cmd}\n\n${HELP}`);
      process.exit(2);
  }
}

main().catch((e) => {
  console.error(e instanceof Error ? e.message : String(e));
  process.exit(1);
});
