// Arama arayüzü: Phoneix'in kendi küçük arama penceresi (Aç / Reddet / Hoparlör / Kapat) ve bilgisayarda çalan zil sesi.
// Pencere tek ve tembel oluşturulur. Dynamic Bar seçiliyken pencere gösterilmez ama zil sesi için gizli olarak yaşar.
const { BrowserWindow, screen, ipcMain } = require('electron');
const path = require('path');

let win = null, onAction = () => {}, ready = false, queued = [];
const alive = () => win && !win.isDestroyed();

function ensure() {
  if (alive()) return;
  ready = false;
  win = new BrowserWindow({
    width: 360, height: 118, show: false, frame: false, transparent: true, resizable: false, skipTaskbar: true, alwaysOnTop: true,
    focusable: false, hasShadow: false, title: 'Phoneix arama',
    webPreferences: { preload: path.join(__dirname, 'call-preload.js'), contextIsolation: true, sandbox: true, backgroundThrottling: false }
  });
  win.setAlwaysOnTop(true, 'screen-saver');
  win.setMenu(null);
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.once('did-finish-load', () => { ready = true; queued.splice(0).forEach((m) => win.webContents.send(m[0], m[1])); });
  win.on('closed', () => { win = null; ready = false; });
  win.loadFile(path.join(__dirname, 'call.html'));
}
function tell(ch, payload) {
  ensure();
  if (ready) win.webContents.send(ch, payload); else queued.push([ch, payload]);
}
function place() {
  const wa = screen.getPrimaryDisplay().workArea, [w, h] = win.getSize();
  win.setPosition(Math.round(wa.x + wa.width - w - 16), Math.round(wa.y + wa.height - h - 16));
}

function init(o) {
  onAction = o.onAction;
  ipcMain.on('call:action', (_e, a) => { if (['answer', 'reject', 'hangup'].includes(a)) onAction(a); });
}

// st: { state: 'ringing' | 'active', who, speaker, visible }
function show(st) {
  tell('call', { state: st.state, who: st.who, speaker: !!st.speaker, voip: !!st.voip });
  if (st.visible) { ensure(); place(); if (!win.isVisible()) win.showInactive(); } else if (alive()) win.hide();
}
function ring(on, src) { if (on || alive()) tell('ring', { on: !!on, src: src || '' }); }
function hide() { ring(false); if (alive()) { win.hide(); tell('call', { state: 'idle' }); } }
function destroy() { if (alive()) win.destroy(); }

module.exports = { init, show, ring, hide, destroy };
