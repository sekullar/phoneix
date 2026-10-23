// Ekran yansıtma: scrcpy'yi (Genymobile) çalıştırır. Phoneix kendi protokolünü kullanmaz; telefon USB veya kablosuz hata ayıklama (adb) ile bağlı olmalı.
// scrcpy ve adb aranır: PHONEIX_SCRCPY, ~/Android/scrcpy, userData/scrcpy, PATH. Telefon ekranı scrcpy'nin kendi penceresinde açılır (fare ve klavye telefonu kontrol eder).
const { spawn, execFile } = require('child_process');
const fs = require('fs'), path = require('path'), os = require('os');

let ctx = { userData: () => '', phoneIp: () => '', phoneName: () => '', cmd: () => {}, caps: () => ({}) }, child = null, userStopped = false, reconnecting = false, lastOpts = null, tries = 0, lastError = '', startedAt = 0, devCache = { t: 0, list: [] };
const init = (c) => { ctx = { ...ctx, ...c }; };

const exists = (p) => { try { return fs.statSync(p).isFile(); } catch { return false; } };
function find() {
  const dirs = [process.env.PHONEIX_SCRCPY, path.join(os.homedir(), 'Android', 'scrcpy'), path.join(ctx.userData(), 'scrcpy')].filter(Boolean);
  for (const d of dirs) if (exists(path.join(d, 'scrcpy.exe'))) return { exe: path.join(d, 'scrcpy.exe'), dir: d };
  for (const d of String(process.env.PATH || '').split(path.delimiter)) if (d && exists(path.join(d, 'scrcpy.exe'))) return { exe: path.join(d, 'scrcpy.exe'), dir: d };
  return null;
}
// adb: Android SDK'daki sürüm tercih edilir (aynı anda iki farklı adb sunucusu çakışmasın), yoksa scrcpy'nin kendisi, yoksa PATH
function adbPath(sc) {
  const c = [path.join(os.homedir(), 'Android', 'Sdk', 'platform-tools', 'adb.exe'), process.env.ANDROID_HOME && path.join(process.env.ANDROID_HOME, 'platform-tools', 'adb.exe'), sc && path.join(sc.dir, 'adb.exe')].filter(Boolean);
  return c.find(exists) || 'adb';
}
const run = (file, args, ms = 6000) => new Promise((res) => {
  execFile(file, args, { timeout: ms, windowsHide: true }, (err, stdout, stderr) => res({ ok: !err, out: String(stdout || ''), err: String(stderr || (err && err.message) || '') }));
});

async function devices(force) {
  if (!force && Date.now() - devCache.t < 4000) return devCache.list;
  const sc = find(), r = await run(adbPath(sc), ['devices', '-l']);
  const rows = r.out.split(/\r?\n/).slice(1).map((l) => l.trim().split(/\s+/)).filter((p) => p.length >= 2);
  lastOffline = rows.filter((p) => p[1] === 'offline' || p[1] === 'unauthorized').length;
  const list = rows.filter((p) => p[1] === 'device')
    .map((p) => ({ serial: p[0], wifi: /:\d+$/.test(p[0]) || p[0].includes('_adb-tls'), model: ((p.find((x) => x.startsWith('model:')) || '').slice(6) || '').replace(/_/g, ' ') }));
  devCache = { t: Date.now(), list };
  return list;
}
// USB yoksa: kablosuz hata ayıklama açık ve eşleşmişse adb onu yerel ağda (mDNS) görür; telefonun IP'siyle eşleşeni bağla
async function tryWireless() {
  const ip = ctx.phoneIp();
  const r = await run(adbPath(find()), ['mdns', 'services'], 5000);
  const rows = r.out.split(/\r?\n/).map((l) => l.trim().split(/\s+/)).filter((p) => p.some((x) => x.includes('_adb-tls-connect')));
  const m = rows.find((p) => ip && p.some((x) => x.startsWith(ip + ':'))) || (rows.length === 1 ? rows[0] : null);
  if (!m) return false;
  const addr = m.find((x) => /^\d{1,3}(\.\d{1,3}){3}:\d+$/.test(x));
  if (!addr) return false;
  const c = await run(adbPath(find()), ['connect', addr], 8000);
  return /connected/i.test(c.out);
}

// mDNS her ağda çalışmaz (ör. Windows mobil erişim noktası): telefonun IP'sinde kablosuz hata ayıklama portunu doğrudan tara.
// Önce Linux'un geçici port aralığı (32768-60999), sonra geri kalanı; açık bulunan her adaya adb connect denenir.
const net = require('net');
function scan(ip, lo, hi, ms = 350) {
  return new Promise((resolve) => {
    const open = []; let next = lo, active = 0;
    const pump = () => {
      while (active < 1500 && next <= hi) {
        const p = next++; active++;
        const s = net.connect({ host: ip, port: p, timeout: ms }); const end = () => { s.destroy(); active--; pump(); };
        s.once('connect', () => { open.push(p); end(); }); s.once('timeout', end); s.once('error', end);
      }
      if (active === 0 && next > hi) resolve(open);
    };
    pump();
  });
}
let lastWifiAddr = '', lastOffline = 0;
async function scanConnect() {
  const ip = ctx.phoneIp(); if (!ip) return false;
  const adb = adbPath(find());
  const tryAddr = async (addr) => { const c = await run(adb, ['connect', addr], 8000); return /connected/i.test(c.out) && !/failed/i.test(c.out); };
  if (lastWifiAddr.startsWith(ip + ':') && await tryAddr(lastWifiAddr)) return true;
  for (const [lo, hi] of [[32768, 60999], [1024, 32767], [61000, 65535]]) {
    const ports = await scan(ip, lo, hi);
    for (const p of ports) if (await tryAddr(ip + ':' + p)) { lastWifiAddr = ip + ':' + p; return true; }
  }
  return false;
}

const ADDR = /^\d{1,3}(\.\d{1,3}){3}:\d{2,5}$/;
// Kablosuz hata ayıklama eşleştirmesi (Android 11+, bir kez): telefonda "Eşleştirme koduyla cihazı eşleştir" ekranındaki IP:port ve 6 haneli kod
async function pair(addr, code) {
  addr = String(addr || '').trim(); code = String(code || '').trim();
  if (!ADDR.test(addr)) return { ok: false, error: 'Adres IP:port biçiminde olmalı (ör. 192.168.1.20:41234)' };
  if (!/^\d{6}$/.test(code)) return { ok: false, error: 'Kod 6 haneli olmalı' };
  const r = await run(adbPath(find()), ['pair', addr, code], 20000);
  const text = (r.out + ' ' + r.err).trim();
  if (!/successfully paired/i.test(text)) return { ok: false, error: /fail|wrong|incorrect|refused|timed/i.test(text) ? 'Eşleşme olmadı: kod yanlış ya da süresi dolmuş olabilir. Telefondaki pencereyi kapatmadan tekrar dene.' : text.slice(0, 160) || 'Eşleşme olmadı' };
  devCache = { t: 0, list: [] };
  const connected = await tryWireless();
  return { ok: true, paired: true, connected, text: connected ? 'Eşleşti ve bağlandı' : 'Eşleşti. Kablosuz hata ayıklamanın ana ekranındaki IP:port\'u aşağıya yazıp Bağlan\'a bas.' };
}
async function connect(addr) {
  addr = String(addr || '').trim();
  if (!ADDR.test(addr)) return { ok: false, error: 'Adres IP:port biçiminde olmalı' };
  const r = await run(adbPath(find()), ['connect', addr], 10000);
  devCache = { t: 0, list: [] };
  return /connected/i.test(r.out) ? { ok: true } : { ok: false, error: 'Bağlanılamadı: ' + (r.out + ' ' + r.err).trim().slice(0, 120) };
}

function hint(text) {
  if (/INJECT_EVENTS|SecurityException/i.test(text)) return 'Telefon kontrole izin vermedi. Geliştirici seçenekleri → "USB hata ayıklama (Güvenlik ayarları)" açık olmalı.';
  if (/unauthorized/i.test(text)) return 'Telefonda "USB hata ayıklamaya izin ver" sorusunu onayla.';
  if (/device.*not found|no devices|ADB device/i.test(text)) return 'Telefon bulunamadı. USB ile bağla ya da Kablosuz hata ayıklamayı aç.';
  if (/Could not open video|encoder/i.test(text)) return 'Görüntü başlatılamadı: telefonun ekranı kilitli olabilir ya da başka bir yansıtma açık.';
  return text.trim().split(/\r?\n/).filter(Boolean).slice(-2).join(' ').slice(0, 160) || 'Yansıtma başlatılamadı';
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// Cihaz yoksa sırayla: kablosuz hata ayıklama zaten açıksa bağlan; değilse (kolay mod kuruluysa) telefona açtır ve mDNS'te görünmesini bekle
async function ensureDevice() {
  let list = await devices(true); if (list.length) return list;
  if (await tryWireless()) { list = await devices(true); if (list.length) return list; }
  if (await scanConnect()) { list = await devices(true); if (list.length) return list; } // kablosuz hata ayıklama zaten açıksa portu tara
  const caps = ctx.caps();
  if (caps.secure) {
    ctx.cmd({ type: 'adbwifi', on: true });
    for (let i = 0; i < 8; i++) {
      await sleep(1500);
      if (await tryWireless() || await scanConnect()) { list = await devices(true); if (list.length) return list; }
    }
  }
  return [];
}

// opts: {audio, screenOff, reconnect, extra: [..]} (extra yalnızca testler için: ör. --no-playback --record)
async function start(opts = {}) {
  if (child) return { ok: true, already: true };
  const sc = find(); if (!sc) return { ok: false, error: 'scrcpy bulunamadı' };
  userStopped = false; lastOpts = opts;
  const list = await ensureDevice();
  if (!list.length) return { ok: false, error: lastOffline ? 'Telefon bağlı ama onay bekliyor: telefondaki "kablosuz hata ayıklamaya izin ver" penceresini onayla (Bu ağda her zaman izin ver).' : ctx.caps().secure ? 'Telefon kablosuz bağlanamadı. Telefon aynı ağda mı?' : hint('ADB device not found') };
  ctx.cmd({ type: 'keepalive', on: true }); // yansıtma boyunca telefonun Wi‑Fi'si ve işlemcisi uyumasın
  const dev = list.find((d) => !d.wifi) || list[0];
  const args = ['-s', dev.serial, '--window-title', 'Phoneix · ' + (ctx.phoneName() || dev.model || 'Telefon'), '--max-fps', '60', '--video-bit-rate', '10M'];
  if (opts.audio === false) args.push('--no-audio');
  if (opts.screenOff) args.push('--turn-screen-off');
  if (Array.isArray(opts.extra)) args.push(...opts.extra);
  lastError = ''; startedAt = Date.now();
  let log = '';
  child = spawn(sc.exe, args, { cwd: sc.dir, env: { ...process.env, ADB: adbPath(sc) }, windowsHide: true });
  const keep = (d) => { log = (log + d).slice(-4000); };
  child.stdout.on('data', keep); child.stderr.on('data', keep);
  child.on('exit', (code) => {
    const ranMs = Date.now() - startedAt; child = null;
    if (code && code !== 0 && ranMs < 15000) lastError = hint(log);
    // pencere kullanıcı tarafından kapatılmadıysa (çıkış kodu 0 değilse) bağlantı koptu: yeniden bağlan
    if (!userStopped && code !== 0 && lastOpts && lastOpts.reconnect !== false && tries < 8) { tries = ranMs > 20000 ? 1 : tries + 1; reconnect(); }
    else { ctx.cmd({ type: 'keepalive', on: false }); tries = 0; }
    ctx.changed && ctx.changed();
  });
  child.on('error', (e) => { child = null; lastError = 'scrcpy başlatılamadı: ' + e.message; ctx.changed && ctx.changed(); });
  ctx.changed && ctx.changed();
  // ilk 2,5 sn içinde çöktüyse hatayı hemen döndür
  await new Promise((r) => setTimeout(r, 2500));
  if (!child) return { ok: false, error: lastError || 'Yansıtma başlatılamadı' };
  return { ok: true, device: dev.serial, wifi: dev.wifi };
}
async function reconnect() {
  reconnecting = true; ctx.changed && ctx.changed();
  await sleep(1500 + tries * 1500);
  if (userStopped) { reconnecting = false; return; }
  const r = await start({ ...lastOpts });
  reconnecting = false;
  if (!r.ok && !userStopped) { lastError = r.error || ''; if (tries < 8) { tries++; reconnect(); } else { ctx.cmd({ type: 'keepalive', on: false }); tries = 0; } }
  ctx.changed && ctx.changed();
}
function stop() { userStopped = true; reconnecting = false; tries = 0; ctx.cmd({ type: 'keepalive', on: false }); if (child) { try { child.kill(); } catch {} } }

// Tek seferlik kolay mod: bağlı bir adb cihazına (USB veya kablosuz) Phoneix için WRITE_SECURE_SETTINGS izni verir
async function enableEasy() {
  const list = await devices(true);
  if (!list.length) return { ok: false, error: 'Önce telefonu USB ile bağla (veya kablosuz bağlıyken dene).' };
  const r = await run(adbPath(find()), ['-s', list[0].serial, 'shell', 'pm', 'grant', 'app.phoneix', 'android.permission.WRITE_SECURE_SETTINGS'], 10000);
  if (r.ok && !r.err.trim()) return { ok: true };
  return { ok: false, error: /SecurityException|not a changeable|Neither user/i.test(r.err) ? 'Telefon izin vermedi: Geliştirici seçenekleri → "USB hata ayıklama (Güvenlik ayarları)" açık olmalı.' : (r.err || 'İzin verilemedi').trim().slice(0, 140) };
}

async function status() {
  const sc = find(), list = sc ? await devices() : [];
  return { installed: !!sc, running: !!child, reconnecting, easy: !!ctx.caps().secure, devices: list.map((d) => ({ model: d.model, wifi: d.wifi })), error: lastError };
}
module.exports = { init, start, stop, enableEasy, status, find, devices, pair, connect, _hint: hint };
