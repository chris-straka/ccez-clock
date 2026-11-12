//! The owner's alarms and timer, for agents on the tailnet and ccez-clock
//! on the phone. SQLite on the Mac mini, on 127.0.0.1, put on the tailnet
//! by `tailscale serve` (never Funnel). Beside ccez-words, not inside it:
//! words is live with its own dictionaries and releases, and a clock
//! change shouldn't restart the word server (or the other way round).
//!
//! Agents read and change alarms (`GET/POST/PATCH/DELETE /alarms`) and the
//! timer (`GET/POST /timer`); the phone sends what changed there and gets
//! what changed here in one `POST /sync`, with its state (`GET /phone`).
//! Per alarm, the later change wins by `at` (ms, never later than its
//! arrival), as in ccez-words; `seq` is the server's own count, so a
//! device whose clock is off still gets everything.

use std::sync::{Arc, Mutex};

use axum::extract::{Path, Query, Request, State};
use axum::http::StatusCode;
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};

/// One alarm, in the shape the phone, the server and `clock.ts` share.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Alarm {
    pub id: String,
    /// "07:30", the phone's local time.
    pub time: String,
    /// "mon".."sun"; empty: rings once.
    #[serde(default)]
    pub days: Vec<String>,
    #[serde(default = "yes")]
    pub enabled: bool,
    #[serde(default)]
    pub label: String,
    /// A sound id; empty: the phone's default.
    #[serde(default)]
    pub sound: String,
    #[serde(default = "yes")]
    pub vibrate: bool,
    #[serde(default = "ten")]
    pub snooze: u32,
    /// When it last changed (ms since 1970), anywhere.
    #[serde(default)]
    pub at: i64,
    #[serde(default)]
    pub deleted: bool,
    /// Fields a newer phone may add: kept, not understood.
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

fn yes() -> bool {
    true
}
fn ten() -> u32 {
    10
}

const DAYS: [&str; 7] = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];
const SOUNDS: [&str; 6] = ["chimes", "marimba", "dawn", "kalimba", "musicbox", "pulse"];

impl Alarm {
    /// The same checks the phone makes (Alarm.kt), so nothing it would refuse gets stored.
    pub fn check(&self) -> Result<(), String> {
        let id_ok = !self.id.is_empty() && self.id.len() <= 40 && self.id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_');
        if !id_ok {
            return Err(format!("bad id {:?}", self.id));
        }
        let (h, m) = self.time.split_once(':').ok_or_else(|| format!("time is HH:MM, not {:?}", self.time))?;
        let ok = matches!((h.parse::<u32>(), m.parse::<u32>()), (Ok(h), Ok(m)) if h < 24 && m < 60);
        if !ok || h.len() > 2 || m.len() != 2 {
            return Err(format!("time is HH:MM, not {:?}", self.time));
        }
        if let Some(d) = self.days.iter().find(|d| !DAYS.contains(&d.as_str())) {
            return Err(format!("days are mon..sun, not {d:?}"));
        }
        if !self.sound.is_empty() && !SOUNDS.contains(&self.sound.as_str()) {
            return Err(format!("no sound {:?} ({})", self.sound, SOUNDS.join(", ")));
        }
        if !(1..=60).contains(&self.snooze) {
            return Err("snooze is 1 to 60 minutes".into());
        }
        if self.label.chars().count() > 80 {
            return Err("label is 80 characters at most".into());
        }
        Ok(())
    }

    /// "7:30" → "07:30", days in week order without repeats.
    fn tidy(mut self) -> Self {
        if let Some((h, m)) = self.time.split_once(':') {
            if let Ok(h) = h.trim().parse::<u32>() {
                self.time = format!("{h:02}:{}", m.trim());
            }
        }
        let days: Vec<String> = self.days.iter().map(|d| d.trim().to_lowercase().chars().take(3).collect()).collect();
        self.days = DAYS.iter().filter(|d| days.iter().any(|x| x == *d)).map(|d| d.to_string()).collect();
        if days.iter().any(|d| !DAYS.contains(&d.as_str())) {
            // Keep the bad one so check() names it.
            self.days.extend(days.into_iter().filter(|d| !DAYS.contains(&d.as_str())));
        }
        self.label = self.label.trim().to_string();
        self
    }
}

/// The one timer (TimerModel.kt's shape).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Timer {
    pub state: String,
    #[serde(default)]
    pub total: i64,
    #[serde(default, rename = "endsAt")]
    pub ends_at: i64,
    #[serde(default)]
    pub remaining: i64,
    #[serde(default)]
    pub at: i64,
}

impl Timer {
    fn idle() -> Self {
        Timer { state: "idle".into(), total: 0, ends_at: 0, remaining: 0, at: 0 }
    }
    fn check(&self) -> Result<(), String> {
        match self.state.as_str() {
            "idle" | "paused" => {}
            "running" if self.ends_at > 0 => {}
            "running" => return Err("a running timer needs endsAt".into()),
            s => return Err(format!("timer state is idle, running or paused, not {s:?}")),
        }
        if self.total < 0 || self.remaining < 0 {
            return Err("negative timer".into());
        }
        Ok(())
    }
}

type Db = Arc<Mutex<Connection>>;

#[derive(Clone)]
struct Ctx {
    db: Db,
    /// A bearer token, when the server is reachable from anything wider than the tailnet.
    token: Option<String>,
}

pub fn open(path: &str) -> rusqlite::Result<Connection> {
    let db = Connection::open(path)?;
    db.execute_batch(
        "PRAGMA journal_mode = WAL;
         CREATE TABLE IF NOT EXISTS alarms (
             id TEXT PRIMARY KEY,
             data TEXT NOT NULL,
             at INTEGER NOT NULL,
             seq INTEGER NOT NULL
         );
         CREATE INDEX IF NOT EXISTS alarms_seq ON alarms(seq);
         CREATE TABLE IF NOT EXISTS timer (k TEXT PRIMARY KEY, data TEXT NOT NULL, at INTEGER NOT NULL);
         -- Each phone's last report: what it has scheduled, its health, when it was seen.
         CREATE TABLE IF NOT EXISTS phone (device TEXT PRIMARY KEY, data TEXT NOT NULL, seen INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v INTEGER NOT NULL);",
    )?;
    Ok(db)
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

fn last_seq(tx: &rusqlite::Transaction) -> rusqlite::Result<i64> {
    let kept: i64 = tx.query_row("SELECT COALESCE(MAX(v), 0) FROM meta WHERE k = 'seq'", [], |r| r.get(0))?;
    let rows: i64 = tx.query_row("SELECT COALESCE(MAX(seq), 0) FROM alarms", [], |r| r.get(0))?;
    Ok(kept.max(rows))
}

fn next_seq(tx: &rusqlite::Transaction) -> rusqlite::Result<i64> {
    let s = last_seq(tx)? + 1;
    tx.execute("INSERT INTO meta (k, v) VALUES ('seq', ?1) ON CONFLICT(k) DO UPDATE SET v = MAX(v, ?1)", params![s])?;
    Ok(s)
}

fn get_alarm(tx: &rusqlite::Transaction, id: &str) -> rusqlite::Result<Option<Alarm>> {
    let data: Option<String> = tx.query_row("SELECT data FROM alarms WHERE id = ?1", params![id], |r| r.get(0)).optional()?;
    Ok(data.and_then(|d| serde_json::from_str(&d).ok()))
}

/// Takes each alarm where it's newer than what's kept, as of [now];
/// returns what's kept afterwards for each. A change can't be newer than
/// its arrival (`at` clamped to [now]). A loser that differs sends the
/// winner out again (a new `seq`), so its sender, whose cursor is past
/// it, gets the winner on its next sync.
pub fn apply_at(db: &mut Connection, alarms: Vec<Alarm>, now: i64) -> rusqlite::Result<Vec<Alarm>> {
    let tx = db.transaction()?;
    let mut kept = Vec::new();
    for mut a in alarms {
        a.at = if a.at <= 0 { now } else { a.at.min(now) };
        match get_alarm(&tx, &a.id)? {
            Some(old) if old.at >= a.at => {
                if old != a {
                    let seq = next_seq(&tx)?;
                    tx.execute("UPDATE alarms SET seq = ?2 WHERE id = ?1", params![a.id, seq])?;
                }
                kept.push(old);
            }
            _ => {
                let seq = next_seq(&tx)?;
                tx.execute(
                    "INSERT INTO alarms (id, data, at, seq) VALUES (?1, ?2, ?3, ?4)
                     ON CONFLICT(id) DO UPDATE SET data = excluded.data, at = excluded.at, seq = excluded.seq",
                    params![a.id, serde_json::to_string(&a).unwrap_or_default(), a.at, seq],
                )?;
                kept.push(a);
            }
        }
    }
    tx.commit()?;
    Ok(kept)
}

/// Alarms changed after [since] (tombstones too), and the `since` to send next.
pub fn changes(db: &mut Connection, since: i64) -> rusqlite::Result<(Vec<Alarm>, i64)> {
    let tx = db.transaction()?;
    let seq = last_seq(&tx)?;
    let list = {
        let mut q = tx.prepare("SELECT data FROM alarms WHERE seq > ?1 ORDER BY seq")?;
        q.query_map(params![since], |r| r.get::<_, String>(0))?
            .filter_map(Result::ok)
            .filter_map(|d| serde_json::from_str(&d).ok())
            .collect()
    };
    drop(tx);
    Ok((list, seq))
}

pub fn timer(db: &Connection) -> rusqlite::Result<Timer> {
    let d: Option<String> = db.query_row("SELECT data FROM timer WHERE k = 'timer'", [], |r| r.get(0)).optional()?;
    Ok(d.and_then(|d| serde_json::from_str(&d).ok()).unwrap_or_else(Timer::idle))
}

/// The timer, if [t] is newer than the one kept; returns the one kept.
pub fn set_timer_at(db: &Connection, mut t: Timer, now: i64) -> rusqlite::Result<Timer> {
    t.at = if t.at <= 0 { now } else { t.at.min(now) };
    let old = timer(db)?;
    if t.at <= old.at {
        return Ok(old);
    }
    db.execute(
        "INSERT INTO timer (k, data, at) VALUES ('timer', ?1, ?2) ON CONFLICT(k) DO UPDATE SET data = excluded.data, at = excluded.at",
        params![serde_json::to_string(&t).unwrap_or_default(), t.at],
    )?;
    Ok(t)
}

// ---- HTTP

struct Fail(StatusCode, String);

impl IntoResponse for Fail {
    fn into_response(self) -> Response {
        (self.0, Json(json!({ "error": self.1 }))).into_response()
    }
}

impl From<rusqlite::Error> for Fail {
    fn from(e: rusqlite::Error) -> Self {
        Fail(StatusCode::INTERNAL_SERVER_ERROR, e.to_string())
    }
}

fn bad(msg: impl Into<String>) -> Fail {
    Fail(StatusCode::BAD_REQUEST, msg.into())
}

fn lock(db: &Db) -> std::sync::MutexGuard<'_, Connection> {
    db.lock().unwrap_or_else(|p| p.into_inner())
}

#[derive(Deserialize)]
struct Since {
    #[serde(default)]
    since: i64,
}

/// One alarm or a list.
#[derive(Deserialize)]
#[serde(untagged)]
enum OneOrMany {
    Many(Vec<Value>),
    One(Value),
}

fn parse_alarm(v: Value) -> Result<Alarm, Fail> {
    let a: Alarm = serde_json::from_value(v).map_err(|e| bad(format!("not an alarm: {e}")))?;
    let a = a.tidy();
    a.check().map_err(bad)?;
    Ok(a)
}

async fn list_alarms(State(c): State<Ctx>, Query(s): Query<Since>) -> Result<Json<Value>, Fail> {
    let (alarms, seq) = changes(&mut lock(&c.db), s.since)?;
    Ok(Json(json!({ "alarms": alarms, "seq": seq })))
}

async fn post_alarms(State(c): State<Ctx>, Json(body): Json<OneOrMany>) -> Result<Json<Value>, Fail> {
    let list = match body {
        OneOrMany::Many(v) => v,
        OneOrMany::One(v) => vec![v],
    };
    let alarms = list.into_iter().map(parse_alarm).collect::<Result<Vec<_>, _>>()?;
    let kept = apply_at(&mut lock(&c.db), alarms, now_ms())?;
    Ok(Json(json!({ "alarms": kept })))
}

/// Changes some fields of one alarm (`{"enabled": false}`), stamped now.
async fn patch_alarm(State(c): State<Ctx>, Path(id): Path<String>, Json(patch): Json<Map<String, Value>>) -> Result<Json<Value>, Fail> {
    let mut db = lock(&c.db);
    let old = {
        let tx = db.transaction()?;
        get_alarm(&tx, &id)?
    };
    let old = old.filter(|a| !a.deleted).ok_or_else(|| Fail(StatusCode::NOT_FOUND, format!("no alarm {id}")))?;
    let mut v = serde_json::to_value(&old).map_err(|e| bad(e.to_string()))?;
    let obj = v.as_object_mut().ok_or_else(|| bad("not an object"))?;
    for (k, val) in patch {
        if !matches!(k.as_str(), "id" | "at" | "deleted") {
            obj.insert(k, val);
        }
    }
    let now = now_ms();
    obj.insert("at".into(), json!(now.max(old.at + 1)));
    let a = parse_alarm(v)?;
    let kept = apply_at(&mut db, vec![a], now.max(old.at + 1))?;
    Ok(Json(json!({ "alarms": kept })))
}

async fn delete_alarm(State(c): State<Ctx>, Path(id): Path<String>) -> Result<Json<Value>, Fail> {
    let mut db = lock(&c.db);
    let old = {
        let tx = db.transaction()?;
        get_alarm(&tx, &id)?
    };
    let mut a = old.filter(|a| !a.deleted).ok_or_else(|| Fail(StatusCode::NOT_FOUND, format!("no alarm {id}")))?;
    let now = now_ms().max(a.at + 1);
    a.deleted = true;
    a.enabled = false;
    a.at = now;
    let kept = apply_at(&mut db, vec![a], now)?;
    Ok(Json(json!({ "alarms": kept })))
}

async fn get_timer(State(c): State<Ctx>) -> Result<Json<Timer>, Fail> {
    Ok(Json(timer(&lock(&c.db))?))
}

async fn post_timer(State(c): State<Ctx>, Json(t): Json<Timer>) -> Result<Json<Timer>, Fail> {
    t.check().map_err(bad)?;
    Ok(Json(set_timer_at(&lock(&c.db), t, now_ms())?))
}

async fn get_phone(State(c): State<Ctx>) -> Result<Json<Value>, Fail> {
    let db = lock(&c.db);
    let mut q = db.prepare("SELECT device, data, seen FROM phone ORDER BY seen DESC")?;
    let rows: Vec<Value> = q
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, i64>(2)?)))?
        .filter_map(Result::ok)
        .map(|(device, data, seen)| json!({ "device": device, "seen": seen, "state": serde_json::from_str::<Value>(&data).unwrap_or(Value::Null) }))
        .collect();
    Ok(Json(json!({ "phones": rows })))
}

#[derive(Deserialize)]
struct SyncIn {
    #[serde(default)]
    since: i64,
    #[serde(default)]
    alarms: Vec<Value>,
    #[serde(default)]
    timer: Option<Timer>,
    #[serde(default)]
    state: Option<Value>,
}

/// The phone's one call: its changes in, the server's changes since its cursor out.
async fn sync(State(c): State<Ctx>, Json(body): Json<SyncIn>) -> Result<Json<Value>, Fail> {
    // A bad alarm from the phone is skipped, never the whole sync.
    let alarms: Vec<Alarm> = body.alarms.into_iter().filter_map(|v| parse_alarm(v).ok()).collect();
    let now = now_ms();
    let mut db = lock(&c.db);
    apply_at(&mut db, alarms, now)?;
    if let Some(t) = body.timer.filter(|t| t.check().is_ok()) {
        set_timer_at(&db, t, now)?;
    }
    if let Some(state) = body.state {
        let device = state.get("device").and_then(Value::as_str).unwrap_or("phone").to_string();
        db.execute(
            "INSERT INTO phone (device, data, seen) VALUES (?1, ?2, ?3) ON CONFLICT(device) DO UPDATE SET data = excluded.data, seen = excluded.seen",
            params![device, state.to_string(), now],
        )?;
    }
    let (alarms, seq) = changes(&mut db, body.since)?;
    let t = timer(&db)?;
    Ok(Json(json!({ "alarms": alarms, "seq": seq, "timer": t })))
}

async fn auth(State(c): State<Ctx>, req: Request, next: Next) -> Response {
    if let Some(token) = &c.token {
        let ok = req
            .headers()
            .get("authorization")
            .and_then(|v| v.to_str().ok())
            .is_some_and(|v| v.strip_prefix("Bearer ").is_some_and(|t| t == token));
        if !ok && req.uri().path() != "/health" {
            return Fail(StatusCode::UNAUTHORIZED, "token".into()).into_response();
        }
    }
    next.run(req).await
}

fn app_with(db: Db, token: Option<String>) -> Router {
    let c = Ctx { db, token };
    Router::new()
        .route("/alarms", get(list_alarms).post(post_alarms))
        .route("/alarms/{id}", axum::routing::patch(patch_alarm).delete(delete_alarm))
        .route("/timer", get(get_timer).post(post_timer))
        .route("/phone", get(get_phone))
        .route("/sync", post(sync))
        .route("/health", get(|| async { "ok" }))
        .layer(axum::middleware::from_fn_with_state(c.clone(), auth))
        .with_state(c)
}

pub fn app(db: Db) -> Router {
    app_with(db, None)
}

#[tokio::main]
async fn main() {
    let path = std::env::var("CCEZ_CLOCK_DB").unwrap_or_else(|_| {
        let dir = format!("{}/.ccez-clock", std::env::var("HOME").unwrap_or_default());
        let _ = std::fs::create_dir_all(&dir);
        format!("{dir}/clock.sqlite")
    });
    let port = std::env::var("CCEZ_CLOCK_PORT").unwrap_or_else(|_| "8799".into());
    let token = std::env::var("CCEZ_CLOCK_TOKEN").ok().filter(|t| !t.is_empty());
    let db = Arc::new(Mutex::new(open(&path).expect("open the database")));
    // Loopback only: tailscale serve puts it on the tailnet.
    let listener = tokio::net::TcpListener::bind(format!("127.0.0.1:{port}")).await.expect("bind");
    axum::serve(listener, app_with(db, token)).await.expect("serve");
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::Request as Req;
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    fn mem() -> Db {
        Arc::new(Mutex::new(open(":memory:").unwrap()))
    }

    fn alarm(id: &str, time: &str, at: i64) -> Alarm {
        serde_json::from_value(json!({ "id": id, "time": time, "at": at })).unwrap()
    }

    async fn call(db: &Db, method: &str, uri: &str, body: Option<Value>) -> (StatusCode, Value) {
        let req = Req::builder().method(method).uri(uri).header("content-type", "application/json");
        let req = req.body(body.map_or(Body::empty(), |b| Body::from(b.to_string()))).unwrap();
        let res = app(db.clone()).oneshot(req).await.unwrap();
        let status = res.status();
        let bytes = res.into_body().collect().await.unwrap().to_bytes();
        (status, serde_json::from_slice(&bytes).unwrap_or(Value::Null))
    }

    #[test]
    fn the_later_change_wins_and_a_loser_gets_the_winner_back() {
        let db = mem();
        let mut c = lock(&db);
        apply_at(&mut c, vec![alarm("a", "07:00", 100)], 1000).unwrap();
        let (_, seq1) = changes(&mut c, 0).unwrap();
        // An older change loses, and the winner goes out again past the old cursor.
        let kept = apply_at(&mut c, vec![alarm("a", "08:00", 50)], 1000).unwrap();
        assert_eq!(kept[0].time, "07:00");
        let (again, _) = changes(&mut c, seq1).unwrap();
        assert_eq!(again.len(), 1);
        assert_eq!(again[0].time, "07:00");
        // A newer one wins.
        apply_at(&mut c, vec![alarm("a", "09:15", 200)], 1000).unwrap();
        let (all, _) = changes(&mut c, 0).unwrap();
        assert_eq!(all[0].time, "09:15");
        // An echo of what's kept changes nothing and sends nothing.
        let (_, seq2) = changes(&mut c, 0).unwrap();
        apply_at(&mut c, vec![all[0].clone()], 1000).unwrap();
        assert!(changes(&mut c, seq2).unwrap().0.is_empty());
    }

    #[test]
    fn a_clock_running_ahead_cannot_lock_an_alarm() {
        let db = mem();
        let mut c = lock(&db);
        let kept = apply_at(&mut c, vec![alarm("a", "07:00", 10_000_000)], 1000).unwrap();
        assert_eq!(kept[0].at, 1000);
        apply_at(&mut c, vec![alarm("a", "06:00", 1001)], 2000).unwrap();
        assert_eq!(changes(&mut c, 0).unwrap().0[0].time, "06:00");
    }

    #[test]
    fn checks_alarms_like_the_phone() {
        assert!(alarm("a", "07:00", 1).check().is_ok());
        assert!(alarm("a", "7:00", 1).tidy().check().is_ok());
        assert_eq!(alarm("a", "7:00", 1).tidy().time, "07:00");
        assert!(alarm("a", "24:00", 1).check().is_err());
        assert!(alarm("a", "07:5", 1).check().is_err());
        assert!(alarm("a b", "07:00", 1).check().is_err());
        let mut d = alarm("a", "07:00", 1);
        d.days = vec!["Fri".into(), "mon".into(), "mon".into()];
        assert_eq!(d.clone().tidy().days, vec!["mon", "fri"]);
        d.days = vec!["funday".into()];
        assert!(d.tidy().check().is_err());
    }

    #[tokio::test]
    async fn agents_and_the_phone_over_http() {
        let db = mem();
        // An agent adds an alarm.
        let (s, v) = call(&db, "POST", "/alarms", Some(json!({ "id": "gym", "time": "6:45", "days": ["mon", "wed"], "label": "Gym" }))).await;
        assert_eq!(s, StatusCode::OK, "{v}");
        assert_eq!(v["alarms"][0]["time"], "06:45");
        // The phone syncs from scratch: it gets it, and reports itself.
        let (s, v) = call(&db, "POST", "/sync", Some(json!({ "since": 0, "alarms": [], "state": { "device": "S24", "next": null } }))).await;
        assert_eq!(s, StatusCode::OK);
        assert_eq!(v["alarms"].as_array().unwrap().len(), 1);
        let seq = v["seq"].as_i64().unwrap();
        // The phone turns it off; the agent sees that.
        let mut off = v["alarms"][0].clone();
        off["enabled"] = json!(false);
        off["at"] = json!(now_ms());
        let (_, v) = call(&db, "POST", "/sync", Some(json!({ "since": seq, "alarms": [off] }))).await;
        let seq = v["seq"].as_i64().unwrap();
        let (_, v) = call(&db, "GET", "/alarms", None).await;
        assert_eq!(v["alarms"][0]["enabled"], false);
        // The agent turns it on and moves it; the phone gets that on its next sync.
        let (s, _) = call(&db, "PATCH", "/alarms/gym", Some(json!({ "enabled": true, "time": "07:00" }))).await;
        assert_eq!(s, StatusCode::OK);
        let (_, v) = call(&db, "POST", "/sync", Some(json!({ "since": seq }))).await;
        assert_eq!(v["alarms"][0]["time"], "07:00");
        assert_eq!(v["alarms"][0]["enabled"], true);
        // Deleting leaves a tombstone the phone gets.
        let seq = v["seq"].as_i64().unwrap();
        let (s, _) = call(&db, "DELETE", "/alarms/gym", None).await;
        assert_eq!(s, StatusCode::OK);
        let (_, v) = call(&db, "POST", "/sync", Some(json!({ "since": seq }))).await;
        assert_eq!(v["alarms"][0]["deleted"], true);
        assert_eq!(call(&db, "DELETE", "/alarms/gym", None).await.0, StatusCode::NOT_FOUND);
        // The phone's report.
        let (_, v) = call(&db, "GET", "/phone", None).await;
        assert_eq!(v["phones"][0]["device"], "S24");
        // A bad alarm is refused plainly.
        let (s, v) = call(&db, "POST", "/alarms", Some(json!({ "id": "x", "time": "25:00" }))).await;
        assert_eq!(s, StatusCode::BAD_REQUEST);
        assert!(v["error"].as_str().unwrap().contains("HH:MM"));
    }

    #[tokio::test]
    async fn the_timer_later_change_wins() {
        let db = mem();
        let (_, v) = call(&db, "GET", "/timer", None).await;
        assert_eq!(v["state"], "idle");
        let ends = now_ms() + 300_000;
        let (s, v) = call(&db, "POST", "/timer", Some(json!({ "state": "running", "total": 300000, "endsAt": ends }))).await;
        assert_eq!(s, StatusCode::OK, "{v}");
        let at = v["at"].as_i64().unwrap();
        // An older phone change loses; the sync answer carries the winner.
        let (_, v) = call(&db, "POST", "/sync", Some(json!({ "timer": { "state": "idle", "at": at - 10 } }))).await;
        assert_eq!(v["timer"]["state"], "running");
        let (s, _) = call(&db, "POST", "/timer", Some(json!({ "state": "running" }))).await;
        assert_eq!(s, StatusCode::BAD_REQUEST);
    }

    #[tokio::test]
    async fn a_token_when_one_is_set() {
        let db = mem();
        let app = app_with(db, Some("s3cret".into()));
        let no = app.clone().oneshot(Req::get("/alarms").body(Body::empty()).unwrap()).await.unwrap();
        assert_eq!(no.status(), StatusCode::UNAUTHORIZED);
        let yes = app.clone().oneshot(Req::get("/alarms").header("authorization", "Bearer s3cret").body(Body::empty()).unwrap()).await.unwrap();
        assert_eq!(yes.status(), StatusCode::OK);
        let health = app.oneshot(Req::get("/health").body(Body::empty()).unwrap()).await.unwrap();
        assert_eq!(health.status(), StatusCode::OK);
    }
}
