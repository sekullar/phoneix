// Phoneix masaüstü uygulaması: telefon eşleştirme ve bağlantı. Dynamic Bar'a bağımlı değildir; Dynamic Bar çalışıyorsa bildirimleri
// onun yerel API'sine (POST /notify) iletir, yoksa Windows bildirimi gösterir.
const { app, BrowserWindow, Tray, Menu, nativeImage, ipcMain, safeStorage, Notification, dialog, shell, clipboard } = require('electron');
const path = require('path'), fs = require('fs'), os = require('os');
const server = require('./server');
const files = require('./files');
const remote = require('./remote');
const mirror = require('./mirror');
const stats = require('./stats');
const deck = require('./deck');
const frame = require('./frame');
const sensor = require('./sensor');
const callui = require('./callui');
sensor.init({ userData: () => app.getPath('userData'), log: (m) => log(m) });
const http = require('http'), crypto = require('crypto');

if (!app.requestSingleInstanceLock()) app.quit();
const alive = (w) => w && !w.isDestroyed();
let win = null, tray = null, quitting = false, activity = [];

// ---------- kalıcı veri: userData/store.json (anahtar Windows DPAPI ile şifreli) ----------
const storeFile = () => path.join(app.getPath('userData'), 'store.json');
let store = { key: '', name: '', lastMsg: 0, saveDir: '', clipSync: true, mirrorAudio: true, mirrorOff: false, callOnBar: true, callRing: true, ringSound: 'app', ringFile: '' };
const replies = new Map(); // yanıtlanabilir bildirimler: id -> {app, title, text}
let call = { state: 'idle', who: '', speaker: false };
let phone = { secure: false, adbwifi: false, battery: -1, charging: false, t: 0, low: false }, lastClip = '', ringing = false;
const saveDir = () => store.saveDir || path.join(app.getPath('downloads'), 'Phoneix');
const load = () => { try { store = { ...store, ...JSON.parse(fs.readFileSync(storeFile(), 'utf8')) }; } catch {} };
const save = () => { try { fs.mkdirSync(app.getPath('userData'), { recursive: true }); fs.writeFileSync(storeFile(), JSON.stringify(store)); } catch {} };
const seal = (v) => (!v ? '' : safeStorage.isEncryptionAvailable() ? 'enc:' + safeStorage.encryptString(v).toString('base64') : 'raw:' + v);
const unseal = (v) => {
  if (typeof v !== 'string') return '';
  try { return v.startsWith('enc:') ? safeStorage.decryptString(Buffer.from(v.slice(4), 'base64')) : v.startsWith('raw:') ? v.slice(4) : ''; } catch { return ''; }
};

function log(...a) {
  try { fs.appendFileSync(path.join(app.getPath('userData'), 'log.txt'), new Date().toISOString() + ' ' + a.join(' ') + '\n'); } catch {}
}
function note(kind, text) { activity = [{ t: Date.now(), kind, text: String(text).slice(0, 120) }, ...activity].slice(0, 30); push(); }

// ---------- Dynamic Bar bağlantısı: ~/.dynbar-ecosystem/dynamic-bar.json ----------
function dynbarLink() {
  try {
    const l = JSON.parse(fs.readFileSync(path.join(os.homedir(), '.dynbar-ecosystem', 'dynamic-bar.json'), 'utf8'));
    return l && l.app === 'dynamic-bar' && Number.isInteger(l.port) ? l : null;
  } catch { return null; }
}
async function dynbarAlive(l) {
  try { return (await (await fetch(`http://127.0.0.1:${l.port}/`, { signal: AbortSignal.timeout(700) })).text()) === 'dynamic-bar'; } catch { return false; }
}
// Bildirimi Dynamic Bar'a yollar; olmazsa Windows bildirimi. Dönen değer: 'bar' | 'windows'
async function forward(n) {
  const l = dynbarLink();
  if (l) {
    try {
      const r = await fetch(`http://127.0.0.1:${l.port}/notify`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', ...(l.token ? { Authorization: 'Bearer ' + l.token } : {}) },
        body: JSON.stringify({ icon: '📱', app: 'Phoneix', mood: 'happy', ttl: 5, ...n }), signal: AbortSignal.timeout(2000)
      });
      if (r.ok) return 'bar';
    } catch {}
  }
  try { if (Notification.isSupported()) new Notification({ title: n.title || 'Phoneix', body: n.text || '' }).show(); } catch {}
  return 'windows';
}

function setPorts(p) {
  if (!Array.isArray(p)) return;
  phone.ports = p.filter((x) => Number.isInteger(x) && x > 1023 && x < 65536).slice(0, 8); phone.portsT = Date.now();
}

// ---------- telefondan gelen mesajlar ----------
async function onMessage(m, first, gone) {
  store.lastMsg = Date.now();
  // Telefondaki monitör / deck modu: sık gelen istekler arayüzü yeniden çizdirmez
  if (m.type === 'ping') return { ok: true };
  if (m.type === 'stats') return { stats: await stats.get() };
  if (m.type === 'deck') return { deck: await deck.layout() };
  if (m.type === 'press') return typeof m.id === 'string' ? deck.press(m.id.slice(0, 20)) : { ok: false, error: 'Geçersiz tuş' };
  if (m.type === 'ringtone') { // telefonun zil sesi: bir kez gelir, diskte saklanır
    try {
      const buf = Buffer.from(String(m.data || ''), 'base64'), mime = String(m.mime || '');
      if (buf.length && buf.length <= 3 * 1024 * 1024 && /^audio\/[\w.+-]+$/.test(mime)) { fs.writeFileSync(phoneRingPath(), buf); fs.writeFileSync(phoneRingPath() + '.mime', mime); note('ring', 'Telefonun zil sesi alındı'); push(); }
      else log('zil sesi reddedildi: ' + mime + ' ' + buf.length);
    } catch (e) { log('zil sesi kaydedilemedi: ' + e.message); }
    return { ok: true };
  }
  if (m.type === 'hello') {
    store.name = String(m.name || 'Telefon').slice(0, 40); save(); syncLink();
    note('pair', 'Eşleşti: ' + store.name);
    forward({ title: 'Telefon eşleşti', text: store.name, force: true });
    if (first && alive(win)) win.webContents.send('paired');
  } else if (m.type === 'test') {
    note('test', 'Telefondan test: ' + String(m.text || '').slice(0, 60)); save();
    forward({ title: String(m.title || 'Phoneix').slice(0, 80), text: String(m.text || 'Telefondan test').slice(0, 200) });
  } else if (m.type === 'clip') {
    if (!store.clipSync) return {};
    const text = typeof m.text === 'string' ? m.text.slice(0, 200000) : '';
    log('clip alındı', text.length, 'karakter', crypto.createHash('sha1').update(text).digest('hex').slice(0, 6));
    if (text && text !== lastClip) { lastClip = text; clipboard.writeText(text); note('clip', 'Telefondan panoya kopyalandı (' + text.length + ' karakter)'); }
  } else if (m.type === 'put') {
    return files.onPut(m);
  } else if (m.type === 'poll') {
    // Uzun yoklama 25 sn tutulur; 90 sn'den uzun sessizlikten sonra gelen ilk yoklama = telefon (yeniden) bağlandı
    setPorts(m.adbports);
    if (Date.now() - phone.t > 90000) { note('online', 'Telefon bağlandı'); forward({ title: 'Telefon bağlandı', text: store.name || 'Telefon', ttl: 3 }); }
    if (Number.isInteger(m.battery) && m.battery >= 0 && m.battery <= 100) { phone = { ...phone, battery: m.battery, charging: !!m.charging, secure: m.secure === true, adbwifi: m.adbwifi === true, ip: typeof m.ip === 'string' && /^\d{1,3}(\.\d{1,3}){3}$/.test(m.ip) ? m.ip : phone.ip, t: Date.now() }; batteryWatch(); }
    return files.onPoll(gone);
  } else if (m.type === 'adbport') {
    setPorts(m.ports); phone.adbwifi = true; // telefon kablosuz hata ayıklamayı açıp portunu buldu
  } else if (m.type === 'get') {
    return files.onGet(m);
  } else if (m.type === 'done') {
    return files.onDone(m);
  } else if (m.type === 'notif') {
    const str = (v, n) => (typeof v === 'string' ? v.slice(0, n) : '');
    const app = str(m.app, 40) || 'Telefon';
    note('notif', app + (m.title && m.title !== app ? ': ' + str(m.title, 50) : ''));
    const n = { title: str(m.title, 80) || app, text: str(m.text, 200), app, mood: 'phone' }; // Dynamic Bar'da maskot telefona bakar (eski sürümler bu değeri yok sayar)
    const rid = typeof m.reply === 'string' && /^[0-9a-f]{6,32}$/.test(m.reply) ? m.reply : '';
    if (rid && apiPort) { // yanıtlanabilir: adada "Yanıtla" düğmesi çıkar
      replies.set(rid, { id: rid, app, title: n.title, text: n.text }); if (replies.size > 40) replies.delete(replies.keys().next().value);
      Object.assign(n, { ttl: 12, actions: [{ id: 'reply', label: 'Yanıtla', input: true }], callback: cbUrl(), data: 'reply:' + rid });
    }
    forward(n).then((v) => log('bildirim →', v, app, rid ? '(yanıtlanabilir)' : ''));
  } else if (m.type === 'replyres') {
    const ok = m.ok === true;
    note('reply', ok ? 'Yanıt gitti' : 'Yanıt gönderilemedi');
    forward({ title: ok ? 'Yanıt gitti ✓' : 'Yanıt gönderilemedi', text: ok ? '' : str(m.error, 80) || 'Bildirim artık yanıtlanamıyor olabilir', icon: ok ? '↩️' : '⚠️', ttl: 3, mood: ok ? 'happy' : 'sad' });
  } else if (m.type === 'call') {
    onCall(m);
  }
  push();
  return {};
}

// ---------- arayüz durumu ----------
async function state() {
  const l = dynbarLink(), s = server.status();
  return {
    paired: !!store.key, name: store.name, lastMsg: store.lastMsg, listening: s.listening, port: s.port, pairing: s.pairing, ips: s.ips,
    dynbar: l ? await dynbarAlive(l) : false, mirror: await mirror.status(), phoneIp: server.status().lastIp, mirrorAudio: store.mirrorAudio !== false, mirrorReconnect: store.mirrorReconnect !== false, mirrorOff: !!store.mirrorOff, transfers: files.list(), replies: [...replies.values()].reverse().slice(0, 5), phone: phone.t ? { battery: phone.battery, charging: phone.charging, t: phone.t } : null, clipSync: store.clipSync, callOnBar: store.callOnBar !== false, callRing: store.callRing !== false, ringSound: store.ringSound || 'app', ringFile: store.ringFile ? path.basename(store.ringFile) : '', phoneRing: fs.existsSync(phoneRingPath()), ringing, saveDir: saveDir(), outPending: files.pendingCount(), autostart: app.getLoginItemSettings().openAtLogin, activity: activity.slice(0, 12)
  };
}
let pushT = null;
function push() { clearTimeout(pushT); pushT = setTimeout(async () => { if (alive(win)) win.webContents.send('state', await state()); }, 80); }

ipcMain.handle('state', () => state());

// ---------- pil, "telefonumu çaldır", pano eşitleme ----------
function batteryWatch() { // pil azalınca bir kez haber ver; şarja takılınca veya yükselince yeniden silahlanır
  if (phone.battery > 30 || phone.charging) phone.low = false;
  else if (phone.battery <= 15 && !phone.low) { phone.low = true; forward({ title: 'Telefonun pili azaldı', text: '%' + phone.battery + ' kaldı', icon: '🪫' }); }
  push();
}
// Yansıtmayı çerçeveli başlatır: telefon biçimli bir pencere açılır ("Bağlanılıyor"), bağlanınca scrcpy ekranı onun içine oturur (frame.js)
async function startMirror() {
  const s = await mirror.status();
  if (!s.running) await frame.open({ name: store.name || 'Telefon', size: store.mirrorSize });
  const r = await mirror.start({ audio: store.mirrorAudio !== false, screenOff: !!store.mirrorOff, reconnect: store.mirrorReconnect !== false });
  if (!r.ok) { note('mirror', r.error); frame.setState('error', r.error || ''); setTimeout(() => { if (!mirror.isRunning()) frame.close(); }, 3200); }
  else if (!r.already) note('mirror', 'Ekran yansıtılıyor');
  push(); return r;
}
function stopMirror() { mirror.stop(); frame.close(); push(); } // scrcpy kapanınca çerçeve de kapanır
frame.onClose(() => stopMirror());
ipcMain.handle('sensor:status', () => sensor.status());
ipcMain.handle('sensor:setup', () => sensor.setup());
ipcMain.handle('sensor:remove', () => sensor.remove());
ipcMain.handle('mirror:start',() => startMirror());
ipcMain.handle('mirror:pair', (_e, addr, code) => mirror.pair(addr, code).then((r) => { push(); return r; }));
ipcMain.handle('mirror:connect', (_e, addr) => mirror.connect(addr).then((r) => { push(); return r; }));
ipcMain.handle('mirror:easy', () => mirror.enableEasy().then((r) => { push(); return r; }));
ipcMain.on('mirror:stop', () => stopMirror());
ipcMain.on('mirror:opts', (_e, o) => { if (o && typeof o === 'object') { if (typeof o.audio === 'boolean') store.mirrorAudio = o.audio; if (typeof o.screenOff === 'boolean') store.mirrorOff = o.screenOff; if (typeof o.reconnect === 'boolean') store.mirrorReconnect = o.reconnect; save(); push(); } });
ipcMain.handle('reply:send', (_e, id, text) => sendReply(id, text));
// Telefon modları: bilgisayardan "telefonda şu modu aç"; deck düzeni burada tanımlanır
ipcMain.on('mode:open', (_e, mode) => { if (!['monitor', 'deck'].includes(mode)) return; remote.push({ type: 'mode', mode }); note('mode', mode === 'monitor' ? 'Telefonda sistem monitörü açılıyor' : 'Telefonda deck açılıyor'); });
ipcMain.handle('deck:get', () => ({ ...deck.get(), allowCmd: !!store.deckCmd }));
ipcMain.handle('deck:save', (_e, c) => { deck.save(c); push(); return deck.get(); });
ipcMain.on('deck:allowcmd', (_e, on) => { store.deckCmd = !!on; save(); push(); });
ipcMain.handle('deck:test', (_e, id) => deck.press(String(id).slice(0, 20)));
const iconCache = new Map();
// Başlat menüsündeki kısayollar (Deck'te "uygulama aç" için liste)
ipcMain.handle('deck:apps', () => {
  const roots = [path.join(process.env.ProgramData || 'C:\ProgramData', 'Microsoft', 'Windows', 'Start Menu', 'Programs'), path.join(process.env.APPDATA || '', 'Microsoft', 'Windows', 'Start Menu', 'Programs')];
  const out = new Map();
  const walk = (dir, depth) => {
    let ents = []; try { ents = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const e of ents) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) { if (depth < 3) walk(p, depth + 1); }
      else if (/.lnk$/i.test(e.name) && !/uninstall|kaldır|documentation|readme|help/i.test(e.name)) { const name = e.name.replace(/.lnk$/i, ''); if (!out.has(name.toLowerCase())) out.set(name.toLowerCase(), { name, path: p }); }
      if (out.size > 600) return;
    }
  };
  roots.forEach((r) => walk(r, 0));
  return [...out.values()].sort((a, b) => a.name.localeCompare(b.name, 'tr'));
});
ipcMain.handle('deck:pickapp', async () => { const r = await dialog.showOpenDialog(win, { properties: ['openFile'] }); return r.canceled ? '' : r.filePaths[0]; });
ipcMain.on('ring', (_e, on) => { ringing = !!on; remote.push({ type: on ? 'ring' : 'stopring' }); note('ring', on ? 'Telefon çaldırılıyor' : 'Çalma durduruldu'); if (on) setTimeout(() => { ringing = false; push(); }, 60000); push(); });
ipcMain.on('clipsync', (_e, on) => { store.clipSync = !!on; save(); push(); });
// Zil sesi: Phoneix melodisi (src boş), seçilen dosya ya da telefonun gönderdiği zil sesi. Pencereye data: adresi olarak gider.
const MIME = { '.mp3': 'audio/mpeg', '.wav': 'audio/wav', '.ogg': 'audio/ogg', '.m4a': 'audio/mp4', '.aac': 'audio/aac', '.opus': 'audio/ogg' };
const phoneRingPath = () => path.join(app.getPath('userData'), 'phone-ring.bin');
let ringCache = { key: '', src: '' };
function ringSrc() {
  try {
    const mode = store.ringSound || 'app';
    let p = '', mime = '';
    if (mode === 'file' && store.ringFile) { p = store.ringFile; mime = MIME[path.extname(p).toLowerCase()]; }
    else if (mode === 'phone') { p = phoneRingPath(); try { mime = fs.readFileSync(p + '.mime', 'utf8').trim(); } catch {} }
    if (!p || !mime) return '';
    const st = fs.statSync(p); if (st.size > 6 * 1024 * 1024) return '';
    const key = p + st.mtimeMs;
    if (ringCache.key !== key) ringCache = { key, src: 'data:' + mime + ';base64,' + fs.readFileSync(p).toString('base64') };
    return ringCache.src;
  } catch { return ''; }
}
ipcMain.handle('ring:pick', async () => {
  const r = await dialog.showOpenDialog(win, { properties: ['openFile'], filters: [{ name: 'Ses', extensions: ['mp3', 'wav', 'ogg', 'm4a', 'aac', 'opus'] }] });
  if (r.canceled || !r.filePaths[0]) return { ok: false };
  let sz = 0; try { sz = fs.statSync(r.filePaths[0]).size; } catch {}
  if (sz > 6 * 1024 * 1024) return { ok: false, error: 'Dosya 6 MB sınırını aşıyor' };
  store.ringFile = r.filePaths[0]; store.ringSound = 'file'; save(); push(); return { ok: true };
});
ipcMain.on('ring:test', () => { callui.ring(true, ringSrc()); setTimeout(() => callui.ring(false), 4000); });
ipcMain.on('callopts', (_e, o) => { if (o && typeof o === 'object') { if (typeof o.onBar === 'boolean') store.callOnBar = o.onBar; if (typeof o.ring === 'boolean') store.callRing = o.ring; if (['app', 'file', 'phone'].includes(o.sound)) { store.ringSound = o.sound; if (o.sound === 'phone') remote.push({ type: 'getring' }); } save(); push(); } });
// Windows panosu: metin değişince telefona gönder. Parola yöneticilerinin "izleme dışı" işaretlediği içerik atlanır. Başlangıçtaki pano gönderilmez.
let clipSeen = false;
setInterval(() => {
  if (!store.key || !store.clipSync) return;
  try {
    const fmts = clipboard.availableFormats();
    const t = clipboard.readText();
    if (!clipSeen) { clipSeen = true; lastClip = t; return; }
    if (!t || t === lastClip || t.length > 200000 || fmts.includes('ExcludeClipboardContentFromMonitorProcessing')) { if (t) lastClip = t; return; }
    lastClip = t; remote.push({ type: 'clip', text: t });
  } catch {}
}, 700).unref();

// ---------- dosya aktarımı: arayüz ve yerel API ----------
async function sendFiles(paths) {
  if (!store.key) return { ok: false, error: 'Telefon eşleşmemiş' };
  const r = await files.enqueue(paths);
  if (!r) return { ok: false, error: 'Gönderilecek dosya yok (klasörler desteklenmiyor)' };
  note('file-out', r.files + ' dosya telefona sıraya alındı');
  return { ok: true, ...r, online: Date.now() - store.lastMsg < 60000 };
}
ipcMain.handle('files:pick', async () => {
  const r = await dialog.showOpenDialog(win, { properties: ['openFile', 'multiSelections'] });
  return r.canceled ? null : sendFiles(r.filePaths);
});
ipcMain.handle('files:send', (_e, paths) => sendFiles(paths));
ipcMain.on('files:open', () => { fs.mkdirSync(saveDir(), { recursive: true }); shell.openPath(saveDir()); });
ipcMain.handle('files:choose', async () => {
  const r = await dialog.showOpenDialog(win, { properties: ['openDirectory', 'createDirectory'], defaultPath: saveDir() });
  if (!r.canceled && r.filePaths[0]) { store.saveDir = r.filePaths[0]; save(); push(); }
});

// Dynamic Bar (kargo) ve diğer yerel programlar için: 127.0.0.1'de küçük API, bulunması için ~/.dynbar-ecosystem/phoneix.json
const LINK = path.join(os.homedir(), '.dynbar-ecosystem', 'phoneix.json');
const apiToken = crypto.randomBytes(24).toString('hex');
let api = null, apiPort = 0;
function syncLink() {
  if (!apiPort) return;
  try {
    fs.mkdirSync(path.dirname(LINK), { recursive: true });
    fs.writeFileSync(LINK, JSON.stringify({ app: 'phoneix', port: apiPort, token: apiToken, pid: process.pid, paired: !!store.key, phone: store.name || '' }));
  } catch {}
}
function removeLink() { try { if (JSON.parse(fs.readFileSync(LINK, 'utf8')).pid === process.pid) fs.unlinkSync(LINK); } catch {} }
// ---------- Dynamic Bar düğmeleri (geri çağrı) ve arama kartı ----------
const cbSecret = crypto.randomBytes(12).toString('hex');
const cbUrl = () => 'http://127.0.0.1:' + apiPort + '/cb/' + cbSecret;
async function bar(route, body) {
  const l = dynbarLink(); if (!l) return false;
  try {
    const r = await fetch('http://127.0.0.1:' + l.port + route, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(l.token ? { Authorization: 'Bearer ' + l.token } : {}) }, body: JSON.stringify(body), signal: AbortSignal.timeout(2000) });
    return r.ok;
  } catch { return false; }
}
function sendReply(id, text) {
  const info = replies.get(id), t = typeof text === 'string' ? text.trim().slice(0, 2000) : '';
  if (!info || !t) return false;
  remote.push({ type: 'reply', id, text: t }); note('reply', 'Yanıt gönderiliyor: ' + info.title); return true;
}
function onBarAction(action, data, text) {
  if (typeof action !== 'string' || typeof data !== 'string') return;
  if (data.startsWith('reply:') && action === 'reply') {
    if (typeof text === 'string' && text.trim()) sendReply(data.slice(6), text); // adada yazılan yanıt
    else showWindow(); // adada yazılamadı: uygulamadaki yanıt kutusu
  } else if (data === 'call' && ['answer', 'reject', 'hangup'].includes(action)) {
    if (action === 'reject') call.rejected = true;
    remote.push({ type: 'call', action });
  }
}
// Rahatsız Etmeyin: telefonda açıksa (mesajla gelir) ya da Dynamic Bar'da DND / sessiz saat / odak varsa arama bilgisayarda gösterilmez, çalmaz
async function dndNow(m) {
  if (m.dnd === true) return 'telefon';
  const l = dynbarLink();
  if (l) {
    try {
      const r = await fetch(`http://127.0.0.1:${l.port}/status`, { headers: l.token ? { Authorization: 'Bearer ' + l.token } : {}, signal: AbortSignal.timeout(800) });
      const s = await r.json();
      if (s && (s.dnd || s.quiet || s.focus)) return 'Dynamic Bar';
    } catch {}
  }
  return '';
}
let lastCallKey = '', lastCallAt = 0;
async function onCall(m) { // telefon arama durumunu bildirir: Dynamic Bar'da ya da Phoneix'in kendi penceresinde Aç / Reddet / Hoparlör / Kapat
  const st = m.state, who = typeof m.who === 'string' && m.who.trim() ? m.who.trim().slice(0, 60) : st === 'ringing' ? 'Gelen arama' : 'Bilinmeyen numara', speaker = m.speaker === true;
  const key = st + '|' + who + '|' + speaker;
  if (key === lastCallKey && Date.now() - lastCallAt < 2000) return; // Samsung görüşme başında aynı durumu birkaç kez bildirir
  lastCallKey = key; lastCallAt = Date.now();
  log('arama', st, who, speaker ? '(hoparlör)' : '');
  const fw = (n) => forward(n).then((v) => log('arama kartı →', v, n.title || ''));
  const base = { key: 'call', icon: '📞', priority: 'high', sticky: true, callback: apiPort ? cbUrl() : undefined, data: 'call' };

  if (st === 'ended') {
    const ringing = call.state === 'ringing', rejected = !!call.rejected, talked = call.state === 'active', was = call.who, hidden = call.hidden;
    call = { state: 'idle', who: '', speaker: false };
    callui.hide(); bar('/dismiss', { key: 'call' });
    const missed = ringing && !rejected;
    if (missed) { note('call', 'Cevapsız arama: ' + was); if (!hidden) fw({ title: 'Cevapsız arama', text: was, icon: '📵', mood: 'sad', ttl: 8 }); }
    else if (!hidden && (rejected || talked) && store.callOnBar !== false) { // kapatma maskotu: kısa bir "tamam, kapandı" kartı
      setTimeout(() => forward({ key: 'call-end', icon: '📞', title: rejected && !talked ? 'Arama reddedildi' : 'Görüşme bitti', text: was, mood: 'hangup', ttl: 3, force: true }), 350);
    }
    return push();
  }
  if (st !== 'ringing' && st !== 'active') return;

  const first = call.state === 'idle' || (st === 'ringing' && call.state !== 'ringing');
  let hidden = call.hidden;
  if (first) { // aramanın başında bir kez karar verilir
    const dnd = await dndNow(m);
    hidden = !!dnd; if (dnd) log('arama: rahatsız etmeyin (' + dnd + '), bilgisayarda gösterilmiyor');
  }
  if (st === 'ringing') { call = { state: 'ringing', who, speaker: false, hidden, rejected: call.rejected }; note('call', 'Arıyor: ' + who); }
  else { if (call.state !== 'active') note('call', 'Görüşme: ' + who); call = { state: 'active', who, speaker, hidden }; }

  if (hidden) { callui.hide(); return push(); }
  const l = dynbarLink(), useBar = store.callOnBar !== false && !!l && await dynbarAlive(l);
  callui.ring(st === 'ringing' && store.callRing !== false, ringSrc());
  if (useBar) {
    callui.show({ state: st, who, speaker, visible: false });
    if (st === 'ringing') fw({ ...base, title: who, text: 'Arıyor…', mood: 'call', ttl: 60, actions: apiPort ? [{ id: 'answer', label: 'Aç', keep: true }, { id: 'reject', label: 'Reddet', keep: true }] : undefined });
    else fw({ ...base, title: who, text: 'Görüşmede', mood: 'talk', ttl: 60, actions: apiPort ? [{ id: 'hangup', label: 'Kapat', keep: true }] : undefined });
  } else {
    bar('/dismiss', { key: 'call' });
    callui.show({ state: st, who, speaker, visible: true });
  }
  push();
}

function startApi() {
  api = http.createServer((req, res) => {
    const out = (code, o) => { res.statusCode = code; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(o)); };
    if (req.method === 'GET' && req.url === '/') return res.end('phoneix');
    if (req.method === 'POST' && req.url === '/cb/' + cbSecret) { // Dynamic Bar'dan düğme geri çağrısı (gizli yol = kimlik doğrulama)
      let body = ''; req.on('data', (d) => { body += d; if (body.length > 1e4) req.destroy(); });
      req.on('end', () => { try { const j = JSON.parse(body); onBarAction(j.action, j.data, j.text); } catch {} out(200, { ok: true }); });
      return;
    }
    const a = Buffer.from(String(req.headers.authorization || '')), b = Buffer.from('Bearer ' + apiToken);
    if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return out(401, { ok: false, error: 'unauthorized' });
    if (req.method === 'GET' && req.url === '/status') return out(200, { ok: true, paired: !!store.key, phone: store.name, battery: phone.t ? phone.battery : null, charging: phone.charging, secure: phone.secure, adbwifi: phone.adbwifi, adbports: phone.ports || [], ip: phone.ip || server.status().lastIp, seenAgo: store.lastMsg ? Date.now() - store.lastMsg : null, ringing });
    if (req.method === 'POST' && req.url === '/ring') {
      let body = ''; req.on('data', (d) => { body += d; if (body.length > 1e4) req.destroy(); });
      req.on('end', () => { let j = {}; try { j = JSON.parse(body || '{}'); } catch {} if (!store.key) return out(409, { ok: false, error: 'Telefon eşleşmemiş' }); ipcMain.emit('ring', null, j.on !== false); out(200, { ok: true }); });
      return;
    }
    if (req.method === 'POST' && req.url === '/mode') { // telefonda "monitor" veya "deck" modunu aç
      let body = ''; req.on('data', (d) => { body += d; if (body.length > 1e4) req.destroy(); });
      req.on('end', () => { let j = {}; try { j = JSON.parse(body || '{}'); } catch {} if (!store.key) return out(409, { ok: false, error: 'Telefon eşleşmemiş' }); if (!['monitor', 'deck'].includes(j.mode)) return out(400, { ok: false, error: 'mode: monitor | deck' }); ipcMain.emit('mode:open', null, j.mode); out(200, { ok: true }); });
      return;
    }
    if (req.method === 'POST' && req.url === '/mirror') {
      let body = ''; req.on('data', (d) => { body += d; if (body.length > 1e4) req.destroy(); });
      req.on('end', async () => { let j = {}; try { j = JSON.parse(body || '{}'); } catch {} if (j.on === false) { stopMirror(); return out(200, { ok: true }); } out(200, await startMirror()); });
      return;
    }
    if (req.method === 'GET' && req.url.startsWith('/job/')) { const j = files.jobStatus(req.url.slice(5)); return j ? out(200, { ok: true, ...j }) : out(404, { ok: false }); }
    if (req.method === 'POST' && req.url === '/send') {
      let body = ''; req.on('data', (d) => { body += d; if (body.length > 1e6) req.destroy(); });
      req.on('end', async () => {
        let j; try { j = JSON.parse(body); } catch { return out(400, { ok: false, error: 'bad json' }); }
        const r = await sendFiles(j && j.paths); out(r.ok ? 200 : 409, r);
      });
      return;
    }
    out(404, { ok: false });
  });
  api.listen(0, '127.0.0.1', () => { apiPort = api.address().port; syncLink(); });
}
ipcMain.handle('pair', () => server.pair().catch(() => null));
// Telefon uygulaması (APK) indirme adresi: en son Release'teki Phoneix.apk
const APK_URL = 'https://github.com/sekullar/phoneix/releases/latest/download/Phoneix.apk';
ipcMain.handle('apk:info', () => {
  const qr = require('qrcode-generator')(0, 'M'); qr.addData(APK_URL); qr.make();
  return { url: APK_URL, svg: qr.createSvgTag({ cellSize: 4, margin: 2, scalable: true }) };
});
ipcMain.on('apk:open', () => shell.openExternal(APK_URL));
ipcMain.on('cancel', () => { server.cancelPair(); push(); });
ipcMain.on('unpair', () => { store.key = ''; store.name = ''; store.lastMsg = 0; save(); server.sync(); files.reset(); remote.clear(); phone = { battery: -1, charging: false, t: 0, low: false }; syncLink(); note('unpair', 'Eşleştirme kaldırıldı'); });
ipcMain.handle('test', async () => {
  const via = await forward({ title: 'Phoneix', text: 'Bilgisayardan test bildirimi' });
  note('test', via === 'bar' ? 'Test bildirimi Dynamic Bar\'a gitti' : 'Dynamic Bar yok, Windows bildirimi gösterildi');
  return via;
});
ipcMain.on('autostart', (_e, on) => { try { app.setLoginItemSettings({ openAtLogin: !!on, args: ['--hidden'] }); } catch {} push(); });

// ---------- pencere ve tepsi ----------
function showWindow() {
  if (!alive(win)) createWindow();
  if (win.isMinimized()) win.restore();
  win.show(); win.focus();
}
function createWindow() {
  win = new BrowserWindow({
    width: 520, height: 760, minWidth: 420, minHeight: 560, show: false, backgroundColor: '#e8f4ff', title: 'Phoneix',
    icon: path.join(__dirname, 'assets', 'icon.png'), autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true }
  });
  win.setMenu(null);
  win.loadFile(path.join(__dirname, 'index.html'));
  win.once('ready-to-show', () => { if (!process.argv.includes('--hidden')) win.show(); });
  win.on('close', (e) => { if (!quitting) { e.preventDefault(); win.hide(); } }); // kapatınca tepsiye iner
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
}
function createTray() {
  tray = new Tray(nativeImage.createFromPath(path.join(__dirname, 'assets', 'tray.png')));
  tray.setToolTip('Phoneix');
  tray.on('click', showWindow);
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Phoneix\'i aç', click: showWindow },
    { label: 'Telefon ekranını yansıt / durdur', click: () => { mirror.status().then((s) => (s.running ? stopMirror() : startMirror())); } },
    { type: 'separator' },
    { label: 'Çıkış', click: () => { quitting = true; app.quit(); } }
  ]));
}

app.on('second-instance', showWindow);
app.on('window-all-closed', (e) => e.preventDefault());
app.on('will-quit', () => { callui.destroy(); mirror.stop(); stats.stop(); deck.stop(); server.destroy(); removeLink(); try { api.close(); } catch {} });
app.whenReady().then(() => {
  load();
  remote.onWake(files.wake);
  deck.init({
    iconOf: async (p) => { // uygulama simgesi (önbellekli, 64x64 PNG data URL)
      if (iconCache.has(p)) return iconCache.get(p);
      let url = '';
      try { const img = await app.getFileIcon(p, { size: 'large' }); if (!img.isEmpty()) url = img.resize({ width: 64, height: 64 }).toDataURL(); } catch {}
      iconCache.set(p, url); return url;
    },
    userData: () => app.getPath('userData'), openUrl: (u) => shell.openExternal(u), openPath: (p) => shell.openPath(p), allowCmd: () => !!store.deckCmd, log,
    bar: (title, text) => forward({ title: title || 'Deck', text: text || '', icon: '🎛️', ttl: 4 }),
    mirrorToggle: () => { mirror.status().then((s) => (s.running ? stopMirror() : startMirror())); }
  });
  mirror.init({ userData: () => app.getPath('userData'), phoneIp: () => phone.ip || server.status().lastIp, phoneName: () => store.name, layout: (sz) => { if (sz && (!store.mirrorSize || store.mirrorSize.w !== sz.w || store.mirrorSize.h !== sz.h)) { store.mirrorSize = sz; save(); } return frame.layout(sz); }, up: (t) => frame.attach(t), down: (final) => { if (final) frame.close(); else frame.detach('reconnecting'); }, cmd: (c) => remote.push(c), caps: () => ({ secure: phone.secure && Date.now() - phone.t < 120000, ports: phone.adbwifi && Date.now() - (phone.portsT || 0) < 120000 ? phone.ports || [] : [] }), changed: push });
  files.init({ cmds: remote, getDir: saveDir, changed: push, arrived: (name, size) => { note('file-in', 'Dosya geldi: ' + name); forward({ title: 'Telefondan dosya', text: name, icon: '📥' }); } });
  startApi();
  server.init({ getKey: () => unseal(store.key), setKey: (k) => { store.key = seal(k); save(); push(); syncLink(); }, onMessage, log, changed: push });
  callui.init({ onAction: (a) => { if (a === 'reject') call.rejected = true; remote.push({ type: 'call', action: a }); } });
  createTray(); createWindow();
  setInterval(() => { if (alive(win) && win.isVisible()) push(); }, 5000); // "son mesaj" süresi ve Dynamic Bar durumu tazelensin
});
