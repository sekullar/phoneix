// Telefon yansıtma çerçevesi (iOS "iPhone Yansıtma" hissi): yansıtma başlarken ekranda telefon biçimli bir pencere yumuşakça açılır ve
// "Bağlanılıyor" gösterir; bağlanınca scrcpy penceresi bu çerçevenin ekran alanına birebir oturur ve yavaşça belirir. Çerçeve scrcpy'yi
// izler (taşıma, dönme) ve onun hemen altında durur. Win32 yoksa (koffi yüklenmezse) çerçeve açılmaz, scrcpy kendi penceresinde çalışır.
const { BrowserWindow, screen, ipcMain } = require('electron');
const path = require('path');
const W = require('./winsync');

const PAD = 28, BEZ = 10, TOP = 34, RADIUS = 14; // DIP: gölge payı, yan/alt çerçeve, başlık çubuğu, ekran köşe yarıçapı (frame.html .screen ile aynı)
let win = null, ready = null, name = '', size = { w: 1080, h: 2340 }, geom = null, sc = 0, timer = null, drag = null, dragUntil = 0, onClose = () => {}, hideT = null;
const alive = () => win && !win.isDestroyed();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const outer = (g) => ({ x: g.x - PAD - BEZ, y: g.y - PAD - TOP, width: g.w + 2 * (PAD + BEZ), height: g.h + TOP + BEZ + 2 * PAD });
const phys = (g) => screen.dipToScreenRect(null, { x: g.x, y: g.y, width: g.w, height: g.h });
const dip = (r) => { const d = screen.screenToDipRect(null, { x: r.x, y: r.y, width: r.w, height: r.h }); return { x: d.x, y: d.y, w: d.width, h: d.height }; };

function compute(sz) { // ekran alanı (DIP): çalışma alanına sığan en büyük, ortalı
  const wa = screen.getPrimaryDisplay().workArea;
  let h = Math.min(wa.height - (TOP + BEZ + 2 * PAD) - 16, 920), w = Math.round((h * sz.w) / sz.h);
  const maxW = wa.width - 2 * (BEZ + PAD) - 40;
  if (w > maxW) { w = maxW; h = Math.round((w * sz.h) / sz.w); }
  const tw = w + 2 * (BEZ + PAD), th = h + TOP + BEZ + 2 * PAD;
  return { x: Math.round(wa.x + (wa.width - tw) / 2) + PAD + BEZ, y: Math.round(wa.y + (wa.height - th) / 2) + PAD + TOP, w, h };
}

function create() {
  win = new BrowserWindow({
    width: 400, height: 800, show: false, transparent: true, frame: false, focusable: false, skipTaskbar: true, resizable: false, hasShadow: false,
    thickFrame: false, backgroundColor: '#00000000',
    webPreferences: { preload: path.join(__dirname, 'frame-preload.js'), contextIsolation: true, sandbox: true }
  });
  win.setIgnoreMouseEvents(false);
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  ready = new Promise((res) => win.webContents.once('did-finish-load', res));
  win.loadFile(path.join(__dirname, 'frame.html'));
}
const send = (p) => { if (alive()) win.webContents.send('state', p); };

/** Çerçeveyi açar ve "bağlanılıyor" gösterir. `o`: {name, size:{w,h} (telefon ekranı pikseli, biliniyorsa)} */
async function open(o = {}) {
  if (!W.ok()) return false;
  clearTimeout(hideT);
  name = o.name || 'Telefon'; if (o.size && o.size.w > 0 && o.size.h > 0) size = o.size;
  geom = compute(size); sc = 0;
  if (!alive()) create();
  await ready; if (!alive()) return false;
  win.setBounds(outer(geom));
  const first = !win.isVisible();
  if (first) win.showInactive();
  send({ phase: 'connecting', name });
  return true;
}
const isOpen = () => !!(alive() && win.isVisible());

/** scrcpy için pencere bağımsızlığı ve konumu: ekran alanının tam üstü. Telefonun gerçek boyutu bilindiğinde çağrılır. */
function layout(sz) {
  if (!isOpen() || !W.ok()) return [];
  if (sz && sz.w > 0 && sz.h > 0 && Math.abs(sz.w / sz.h - size.w / size.h) > 0.01) { size = sz; geom = compute(size); win.setBounds(outer(geom)); }
  const r = phys(geom);
  return ['--window-borderless', '--window-x', String(r.x), '--window-y', String(r.y), '--window-width', String(r.width), '--window-height', String(r.height)];
}

async function attach(title) {
  if (!isOpen()) return false;
  const frameH = W.hwndOf(win);
  let h = 0; const t0 = Date.now();
  while (Date.now() - t0 < 10000 && !h) { h = W.find(title); if (!h) await sleep(25); }
  if (!h || !isOpen()) return false;
  sc = h; W.alpha(sc, 0);
  const r = phys(geom); W.place(sc, r.x, r.y, r.width, r.height); W.below(frameH, sc); rounded(r.width, r.height, r.width / geom.w);
  send({ phase: 'connected', name });
  const STEPS = 16; // yumuşak belirme (~0,35 sn)
  for (let i = 1; i <= STEPS; i++) { const t = i / STEPS; W.alpha(sc, 255 * (1 - Math.pow(1 - t, 3))); await sleep(22); }
  W.alpha(sc, 255);
  clearInterval(timer); timer = setInterval(() => tick(frameH), 120);
  return true;
}

let rw = 0, rh = 0;
function rounded(w, h, k) { rw = w; rh = h; W.round(sc, w, h, 2 * RADIUS * k); } // ekran alanının köşeleri çerçevedekiyle aynı yarıçapta
function tick(frameH) {
  if (!alive() || !sc) { clearInterval(timer); return; }
  if (!W.exists(sc)) { sc = 0; clearInterval(timer); return; }
  const r = W.rect(sc); if (!r) return;
  if (Date.now() > dragUntil) { // scrcpy boyutu ya da yeri kendiliğinden değiştiyse (telefon döndü, Win+ok) çerçeve ona uyar
    const cur = phys(geom);
    if (Math.abs(r.w - cur.width) > 2 || Math.abs(r.h - cur.height) > 2 || Math.abs(r.x - cur.x) > 2 || Math.abs(r.y - cur.y) > 2) { geom = dip(r); win.setBounds(outer(geom)); }
  }
  if (r.w !== rw || r.h !== rh) rounded(r.w, r.h, geom ? r.w / geom.w : 1);
  if (W.nextBelow(sc) !== frameH) W.below(frameH, sc); // çerçeve her zaman scrcpy'nin hemen altında
}

/** scrcpy düştü (yeniden bağlanma ya da kapanış): çerçevedeki yükleme ekranı yeniden görünür. */
function detach(phase, text) { clearInterval(timer); sc = 0; send({ phase: phase || 'reconnecting', name, text }); }
function setState(phase, text) { send({ phase, name, text }); }

function close() {
  clearInterval(timer); sc = 0;
  if (!alive() || !win.isVisible()) return;
  send({ phase: 'out' });
  clearTimeout(hideT); hideT = setTimeout(() => { if (alive()) win.hide(); }, 420);
}

ipcMain.on('frame:close', () => onClose());
ipcMain.on('frame:drag', (_e, kind, x, y) => {
  if (!isOpen() || !geom) return;
  if (kind === 'start') { drag = { px: x, py: y, g: { ...geom } }; return; }
  if (kind === 'end') { drag = null; return; }
  if (!drag || !Number.isFinite(x) || !Number.isFinite(y)) return;
  dragUntil = Date.now() + 400;
  geom = { ...drag.g, x: Math.round(drag.g.x + x - drag.px), y: Math.round(drag.g.y + y - drag.py) };
  win.setBounds(outer(geom));
  if (sc) { const r = phys(geom); W.place(sc, r.x, r.y, r.width, r.height); }
});

module.exports = { open, layout, attach, detach, setState, close, isOpen, onClose: (f) => { onClose = f; } };
