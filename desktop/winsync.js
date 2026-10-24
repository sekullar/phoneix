// Win32 pencere yardımcıları (koffi): scrcpy penceresini bulur, çerçeve penceremizin içine yerleştirir, Z sırasını ve saydamlığını ayarlar.
// Pencere tanıtıcıları (HWND) sayı olarak taşınır. Yalnızca Windows'ta çalışır; yüklenemezse hepsi sessizce hiçbir şey yapmaz.
let K = null;
try {
  const koffi = require('koffi');
  const u = koffi.load('user32.dll');
  const RECT = koffi.struct('RECT', { left: 'int32', top: 'int32', right: 'int32', bottom: 'int32' });
  K = {
    FindWindowW: u.func('intptr __stdcall FindWindowW(str16 cls, str16 title)'),
    GetWindowRect: u.func('bool __stdcall GetWindowRect(intptr h, _Out_ RECT *r)'),
    SetWindowPos: u.func('bool __stdcall SetWindowPos(intptr h, intptr after, int x, int y, int cx, int cy, uint32 flags)'),
    IsWindow: u.func('bool __stdcall IsWindow(intptr h)'),
    IsWindowVisible: u.func('bool __stdcall IsWindowVisible(intptr h)'),
    GetWindow: u.func('intptr __stdcall GetWindow(intptr h, uint32 cmd)'),
    GetForegroundWindow: u.func('intptr __stdcall GetForegroundWindow()'),
    GetWindowLongW: u.func('int32 __stdcall GetWindowLongW(intptr h, int idx)'),
    SetWindowLongW: u.func('int32 __stdcall SetWindowLongW(intptr h, int idx, int32 v)'),
    SetLayeredWindowAttributes: u.func('bool __stdcall SetLayeredWindowAttributes(intptr h, uint32 key, uint8 alpha, uint32 flags)'),
    SetWindowRgn: u.func('int __stdcall SetWindowRgn(intptr h, intptr rgn, bool redraw)'),
    CreateRoundRectRgn: koffi.load('gdi32.dll').func('intptr __stdcall CreateRoundRectRgn(int l, int t, int r, int b, int w, int h)'),
    RECT
  };
} catch { K = null; }

const SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_NOACTIVATE = 0x10, SWP_NOZORDER = 0x4;
const GWL_EXSTYLE = -20, WS_EX_LAYERED = 0x80000, LWA_ALPHA = 0x2;
const ok = () => !!K;

const find = (title) => (K ? Number(K.FindWindowW(null, title)) : 0);
const exists = (h) => !!(K && h && K.IsWindow(h));
const visible = (h) => !!(K && h && K.IsWindowVisible(h));
function rect(h) {
  if (!K || !h) return null;
  const r = {}; if (!K.GetWindowRect(h, r)) return null;
  return { x: r.left, y: r.top, w: r.right - r.left, h: r.bottom - r.top };
}
/** Pencereyi (fiziksel piksel) konum ve boyuta yerleştirir; etkinleştirmez. */
const place = (h, x, y, w, hh) => K && K.SetWindowPos(h, 0, Math.round(x), Math.round(y), Math.round(w), Math.round(hh), SWP_NOACTIVATE | SWP_NOZORDER);
/** `h` penceresini `target`'ın hemen altına alır. */
const below = (h, target) => K && K.SetWindowPos(h, target, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
/** Z sırasında `h`'nin hemen altındaki pencere. */
const nextBelow = (h) => (K ? Number(K.GetWindow(h, 2)) : 0);
const foreground = () => (K ? Number(K.GetForegroundWindow()) : 0);
function alpha(h, a) { // 0-255; 255'te katmanlı stil kaldırılır
  if (!K || !h) return;
  const ex = K.GetWindowLongW(h, GWL_EXSTYLE);
  if (a >= 255) { if (ex & WS_EX_LAYERED) K.SetWindowLongW(h, GWL_EXSTYLE, ex & ~WS_EX_LAYERED); return; }
  if (!(ex & WS_EX_LAYERED)) K.SetWindowLongW(h, GWL_EXSTYLE, ex | WS_EX_LAYERED);
  K.SetLayeredWindowAttributes(h, 0, Math.max(0, Math.round(a)), LWA_ALPHA);
}
/** Pencerenin köşelerini yuvarlar (w x hh fiziksel piksel, `dia` köşe çapı); sistem bölgenin sahipliğini devralır. */
function round(h, w, hh, dia) {
  if (!K || !h) return;
  K.SetWindowRgn(h, K.CreateRoundRectRgn(0, 0, Math.round(w) + 1, Math.round(hh) + 1, Math.round(dia), Math.round(dia)), true);
}
const hwndOf =(win) => { try { return Number(win.getNativeWindowHandle().readBigUInt64LE(0)); } catch { return 0; } };

module.exports = { ok, find, exists, visible, rect, place, below, nextBelow, foreground, alpha, round, hwndOf };
