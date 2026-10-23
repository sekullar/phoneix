// Dosya aktarımı. Telefondan gelen: `put` parçaları klasöre yazılır. Bilgisayardan giden: dosyalar bir kuyrukta bekler,
// telefon `poll` ile sorar, `get` ile parça parça indirir, bitince `done` der. Her şey şifreli protokolün içinden geçer (PROTOCOL.md).
const fs = require('fs'), fsp = fs.promises, path = require('path'), crypto = require('crypto');

const CHUNK = 1024 * 1024, MAX_FILES = 50, MAX_SIZE = 8 * 1024 ** 3, POLL_HOLD = 25000, KEEP_OUT = 24 * 3600 * 1000;
let ctx;
const incoming = new Map(); // id -> {name, tmp, size, next, bytes, t}
const outbox = new Map();   // dosya id -> {id, job, name, path, size, sent, done, t}
const jobs = new Map();     // iş id -> {id, ids:[], state, error}
let waiters = [], recent = [];

const init = (c) => { ctx = c; setInterval(sweep, 30000).unref(); };
const dir = () => ctx.getDir();
const rid = () => crypto.randomBytes(8).toString('hex');
const safeName = (n) => {
  let s = String(n || '').replace(/[\\/]/g, '_').replace(/[<>:"|?*\u0000-\u001f]/g, '_').replace(/^\.+/, '').replace(/[ .]+$/, '').slice(0, 150);
  if (/^(con|prn|aux|nul|com\d|lpt\d)(\..*)?$/i.test(s)) s = '_' + s;
  return s || 'dosya';
};
async function uniqueIn(d, name) {
  const ext = path.extname(name), base = path.basename(name, ext);
  for (let i = 1; ; i++) {
    const cand = i === 1 ? name : `${base} (${i})${ext}`;
    try { await fsp.access(path.join(d, cand)); } catch { return cand; }
  }
}
function note(rec) { recent = [rec, ...recent.filter((r) => r.id !== rec.id)].slice(0, 8); ctx.changed(); }

// ---------- telefon -> bilgisayar ----------
async function onPut(m) {
  const id = String(m.id || '');
  if (!/^[0-9a-f]{8,32}$/.test(id) || !Number.isInteger(m.seq) || m.seq < 0 || typeof m.data !== 'string') return { ok: false, error: 'bad' };
  let e = incoming.get(id);
  if (!e) {
    if (m.seq !== 0 || incoming.size >= 8) return { ok: false, error: 'bad' };
    const size = Number.isFinite(m.size) && m.size >= 0 ? m.size : 0;
    if (size > MAX_SIZE) return { ok: false, error: 'big' };
    const d = dir(); await fsp.mkdir(d, { recursive: true });
    e = { name: safeName(m.name), tmp: path.join(d, `.phoneix-${id}.part`), size, next: 0, bytes: 0, t: Date.now() };
    await fsp.writeFile(e.tmp, '');
    incoming.set(id, e);
  }
  if (m.seq !== e.next) return { ok: false, error: 'order' };
  const buf = Buffer.from(m.data, 'base64');
  e.bytes += buf.length;
  if (e.bytes > MAX_SIZE || (e.size && e.bytes > e.size + CHUNK)) { drop(id); return { ok: false, error: 'big' }; }
  await fsp.appendFile(e.tmp, buf);
  e.next++; e.t = Date.now();
  if (!m.last) { note({ id, dir: 'in', name: e.name, size: e.size, done: e.bytes, state: 'active', t: Date.now() }); return { ok: true }; }
  incoming.delete(id);
  const d = dir(), final = await uniqueIn(d, e.name);
  await fsp.rename(e.tmp, path.join(d, final));
  note({ id, dir: 'in', name: final, size: e.bytes, done: e.bytes, state: 'done', t: Date.now() });
  ctx.arrived(final, e.bytes);
  return { ok: true };
}
function drop(id) { const e = incoming.get(id); if (e) { incoming.delete(id); fsp.unlink(e.tmp).catch(() => {}); } }

// ---------- bilgisayar -> telefon ----------
async function enqueue(paths) {
  const list = [], job = { id: rid(), ids: [], state: 'queued', error: '' };
  for (const p of (Array.isArray(paths) ? paths : []).slice(0, MAX_FILES)) {
    if (typeof p !== 'string' || !path.isAbsolute(p)) continue;
    try {
      const st = await fsp.stat(p);
      if (!st.isFile() || st.size > MAX_SIZE) continue;
      list.push({ id: rid(), job: job.id, name: safeName(path.basename(p)), path: p, size: st.size, sent: 0, done: false, t: Date.now() });
    } catch {}
  }
  if (!list.length) return null;
  for (const f of list) { outbox.set(f.id, f); job.ids.push(f.id); note({ id: f.id, dir: 'out', name: f.name, size: f.size, done: 0, state: 'queued', t: Date.now() }); }
  jobs.set(job.id, job);
  wake();
  return { job: job.id, files: list.length };
}
const pending = () => [...outbox.values()].filter((f) => !f.done);
function wake() { const w = waiters; waiters = []; w.forEach((r) => r()); }

// Telefon: "gönderecek bir şey var mı?" Varsa hemen, yoksa en çok POLL_HOLD ms bekleyip döner.
async function onPoll(isGone) {
  if (!pending().length && !ctx.cmds.pending()) await new Promise((res) => { const t = setTimeout(res, POLL_HOLD); waiters.push(() => { clearTimeout(t); res(); }); });
  if (isGone && isGone()) return null;
  return { files: pending().slice(0, 20).map((f) => ({ id: f.id, name: f.name, size: f.size })), cmds: ctx.cmds.take() };
}
async function onGet(m) {
  const f = outbox.get(String(m.id || ''));
  if (!f || f.done || !Number.isInteger(m.seq) || m.seq < 0) return { ok: false, error: 'none' };
  let fh;
  try {
    fh = await fsp.open(f.path, 'r');
    const buf = Buffer.alloc(CHUNK), { bytesRead } = await fh.read(buf, 0, CHUNK, m.seq * CHUNK);
    const last = m.seq * CHUNK + bytesRead >= f.size;
    f.sent = Math.min(f.size, m.seq * CHUNK + bytesRead); f.t = Date.now();
    const job = jobs.get(f.job); if (job && job.state === 'queued') job.state = 'sending';
    note({ id: f.id, dir: 'out', name: f.name, size: f.size, done: f.sent, state: 'active', t: Date.now() });
    return { ok: true, data: buf.subarray(0, bytesRead).toString('base64'), last, size: f.size };
  } catch (err) {
    fail(f, 'Dosya okunamadı');
    return { ok: false, error: 'read' };
  } finally { if (fh) await fh.close().catch(() => {}); }
}
function onDone(m) {
  const f = outbox.get(String(m.id || ''));
  if (!f) return { ok: true };
  f.done = true; f.sent = f.size;
  const job = jobs.get(f.job);
  if (job && job.ids.every((i) => outbox.get(i) && outbox.get(i).done)) job.state = 'done';
  note({ id: f.id, dir: 'out', name: f.name, size: f.size, done: f.size, state: 'done', t: Date.now() });
  return { ok: true };
}
function fail(f, why) {
  f.done = true;
  const job = jobs.get(f.job); if (job) { job.state = 'error'; job.error = why; }
  note({ id: f.id, dir: 'out', name: f.name, size: f.size, done: f.sent, state: 'error', t: Date.now() });
}
function jobStatus(id) {
  const job = jobs.get(String(id)); if (!job) return null;
  const fs_ = job.ids.map((i) => outbox.get(i)).filter(Boolean);
  return { state: job.state, error: job.error, sent: fs_.reduce((a, f) => a + f.sent, 0), total: fs_.reduce((a, f) => a + f.size, 0), files: fs_.length };
}
function sweep() {
  const n = Date.now();
  for (const [id, e] of incoming) if (n - e.t > 120000) drop(id);
  for (const [id, f] of outbox) if (n - f.t > KEEP_OUT) outbox.delete(id);
  for (const [id, j] of jobs) if (!j.ids.some((i) => outbox.has(i))) jobs.delete(id);
}
// Eşleştirme kaldırılınca bekleyen her şey düşer
function reset() { for (const id of [...incoming.keys()]) drop(id); outbox.clear(); jobs.clear(); recent = []; wake(); ctx.changed(); }

module.exports = {
  init, onPut, onPoll, onGet, onDone, enqueue, jobStatus, reset, wake,
  list: () => recent, pendingCount: () => pending().length
};
