// Phoneix masaüstü uygulaması: telefon eşleştirme ve bağlantı. Dynamic Bar'a bağımlı değildir; Dynamic Bar çalışıyorsa bildirimleri
// onun yerel API'sine (POST /notify) iletir, yoksa Windows bildirimi gösterir.
const { app, BrowserWindow, Tray, Menu, nativeImage, ipcMain, safeStorage, Notification } = require('electron');
const path = require('path'), fs = require('fs'), os = require('os');
const server = require('./server');

if (!app.requestSingleInstanceLock()) app.quit();
const alive = (w) => w && !w.isDestroyed();
let win = null, tray = null, quitting = false, activity = [];

// ---------- kalıcı veri: userData/store.json (anahtar Windows DPAPI ile şifreli) ----------
const storeFile = () => path.join(app.getPath('userData'), 'store.json');
let store = { key: '', name: '', lastMsg: 0 };
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

// ---------- telefondan gelen mesajlar ----------
function onMessage(m, first) {
  store.lastMsg = Date.now();
  if (m.type === 'hello') {
    store.name = String(m.name || 'Telefon').slice(0, 40); save();
    note('pair', 'Eşleşti: ' + store.name);
    forward({ title: 'Telefon eşleşti', text: store.name, force: true });
    if (first && alive(win)) win.webContents.send('paired');
  } else if (m.type === 'test') {
    note('test', 'Telefondan test: ' + String(m.text || '').slice(0, 60)); save();
    forward({ title: String(m.title || 'Phoneix').slice(0, 80), text: String(m.text || 'Telefondan test').slice(0, 200) });
  } else if (m.type === 'notif') {
    const str = (v, n) => (typeof v === 'string' ? v.slice(0, n) : '');
    const app = str(m.app, 40) || 'Telefon';
    note('notif', app + (m.title && m.title !== app ? ': ' + str(m.title, 50) : ''));
    forward({ title: str(m.title, 80) || app, text: str(m.text, 200), app });
  }
  push();
  return {};
}

// ---------- arayüz durumu ----------
async function state() {
  const l = dynbarLink(), s = server.status();
  return {
    paired: !!store.key, name: store.name, lastMsg: store.lastMsg, listening: s.listening, port: s.port, pairing: s.pairing, ips: s.ips,
    dynbar: l ? await dynbarAlive(l) : false, autostart: app.getLoginItemSettings().openAtLogin, activity: activity.slice(0, 12)
  };
}
let pushT = null;
function push() { clearTimeout(pushT); pushT = setTimeout(async () => { if (alive(win)) win.webContents.send('state', await state()); }, 80); }

ipcMain.handle('state', () => state());
ipcMain.handle('pair', () => server.pair().catch(() => null));
ipcMain.on('cancel', () => { server.cancelPair(); push(); });
ipcMain.on('unpair', () => { store.key = ''; store.name = ''; store.lastMsg = 0; save(); server.sync(); note('unpair', 'Eşleştirme kaldırıldı'); });
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
    width: 460, height: 700, minWidth: 400, minHeight: 560, show: false, backgroundColor: '#0d0d12', title: 'Phoneix',
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
    { type: 'separator' },
    { label: 'Çıkış', click: () => { quitting = true; app.quit(); } }
  ]));
}

app.on('second-instance', showWindow);
app.on('window-all-closed', (e) => e.preventDefault());
app.on('will-quit', () => server.destroy());
app.whenReady().then(() => {
  load();
  server.init({ getKey: () => unseal(store.key), setKey: (k) => { store.key = seal(k); save(); push(); }, onMessage, log, changed: push });
  createTray(); createWindow();
  setInterval(() => { if (alive(win) && win.isVisible()) push(); }, 5000); // "son mesaj" süresi ve Dynamic Bar durumu tazelensin
});
