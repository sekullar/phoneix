// Deck modu: telefonda bir tuş ızgarası; tuşları bilgisayarda tanımlarsın. Telefon yalnızca "şu id'li tuşa basıldı" der,
// ne çalışacağı burada kayıtlı tanımdan gelir (telefondan komut ya da yol gönderilemez).
const fs = require('fs'), path = require('path');
const { spawn } = require('child_process');

const ACTIONS = ['url', 'app', 'keys', 'media', 'bar', 'mirror', 'lock', 'cmd'];
const MEDIA = { playpause: 0xB3, next: 0xB0, prev: 0xB1, stop: 0xB2, volup: 0xAF, voldown: 0xAE, mute: 0xAD };
const VK = { ctrl: 0x11, control: 0x11, shift: 0x10, alt: 0x12, win: 0x5B, meta: 0x5B, enter: 0x0D, esc: 0x1B, escape: 0x1B, tab: 0x09, space: 0x20, backspace: 0x08, delete: 0x2E, del: 0x2E,
  home: 0x24, end: 0x23, pageup: 0x21, pagedown: 0x22, left: 0x25, up: 0x26, right: 0x27, down: 0x28, insert: 0x2D, printscreen: 0x2C, prtsc: 0x2C };
for (let i = 1; i <= 24; i++) VK['f' + i] = 0x6F + i;
for (let c = 65; c <= 90; c++) VK[String.fromCharCode(c).toLowerCase()] = c;
for (let d = 0; d <= 9; d++) VK[String(d)] = 0x30 + d;
const EXT = new Set([0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x24, 0x23, 0x21, 0x22, 0x5B, ...Object.values(MEDIA)]);

let ctx = { iconOf: async () => '', userData: () => '', openUrl: () => {}, openPath: () => {}, bar: () => {}, mirrorToggle: () => {}, allowCmd: () => false, log: () => {} };
let cfg = null, helper = null;

const starter = () => ({
  pages: [
    { id: 'p1', name: 'Yayın', buttons: [
      { id: 'b1', label: 'Mikrofon', icon: '🎙️', color: '#2f8cff', action: { type: 'keys', value: 'ctrl+shift+m' } },
      { id: 'b2', label: 'Kayıt', icon: '⏺️', color: '#ff5b6e', action: { type: 'keys', value: 'ctrl+shift+r' } },
      { id: 'b3', label: 'Sahne', icon: '🎬', color: '#7c6cff', action: { type: 'keys', value: 'ctrl+shift+1' } },
      { id: 'b4', label: 'Ekran', icon: '📱', color: '#27c4a8', action: { type: 'mirror', value: '' } }
    ] },
    { id: 'p2', name: 'Medya', buttons: [
      { id: 'b5', label: 'Önceki', icon: '⏮️', color: '#4aa8ff', action: { type: 'media', value: 'prev' } },
      { id: 'b6', label: 'Oynat/Duraklat', icon: '⏯️', color: '#2f8cff', action: { type: 'media', value: 'playpause' } },
      { id: 'b7', label: 'Sonraki', icon: '⏭️', color: '#4aa8ff', action: { type: 'media', value: 'next' } },
      { id: 'b8', label: 'Sesi kıs', icon: '🔉', color: '#5ac8fa', action: { type: 'media', value: 'voldown' } },
      { id: 'b9', label: 'Sesi aç', icon: '🔊', color: '#5ac8fa', action: { type: 'media', value: 'volup' } },
      { id: 'b10', label: 'Sessiz', icon: '🔇', color: '#8aa4c8', action: { type: 'media', value: 'mute' } }
    ] }
  ]
});

// Telefonda her zaman görünen, düzenlenemeyen "Güç" sayfası. Tuşlar sabittir; telefon yalnızca id gönderir.
// confirm: telefon basmadan önce onay sorar (geri alınamaz işlemler).
const POWER = [
  { id: 'pw_lock', label: 'Kilitle', icon: '🔒', color: '#2f8cff', run: ['rundll32.exe', ['user32.dll,LockWorkStation']] },
  { id: 'pw_sleep', label: 'Uyku', icon: '🌙', color: '#7c6cff', confirm: 'Bilgisayar uykuya alınsın mı?', run: ['rundll32.exe', ['powrprof.dll,SetSuspendState', '0,1,0']] },
  { id: 'pw_screen', label: 'Ekranı kapat', icon: '🖥️', color: '#27c4a8', screenOff: true },
  { id: 'pw_restart', label: 'Yeniden başlat', icon: '🔄', color: '#ff9f43', confirm: 'Bilgisayar yeniden başlatılsın mı? Kaydedilmemiş işler kaybolabilir.', run: ['shutdown.exe', ['/r', '/t', '3']] },
  { id: 'pw_shutdown', label: 'Kapat', icon: '🔌', color: '#ff5b6e', confirm: 'Bilgisayar kapatılsın mı? Kaydedilmemiş işler kaybolabilir.', run: ['shutdown.exe', ['/s', '/t', '3']] }
];

const file = () => path.join(ctx.userData(), 'deck.json');
const str = (v, n) => (typeof v === 'string' ? v.slice(0, n) : '');
function clean(c) { // dışarıdan gelen (arayüz) yapılandırmayı doğrula
  const pages = (Array.isArray(c && c.pages) ? c.pages : []).slice(0, 8).map((p, pi) => ({
    id: str(p.id, 20) || 'p' + pi, name: str(p.name, 24) || 'Sayfa',
    buttons: (Array.isArray(p.buttons) ? p.buttons : []).slice(0, 24).map((b, bi) => ({
      id: str(b.id, 20) || 'b' + pi + '_' + bi, label: str(b.label, 24), icon: str(b.icon, 8),
      color: /^#[0-9a-fA-F]{6}$/.test(b.color) ? b.color : '#2f8cff',
      action: { type: ACTIONS.includes(b.action && b.action.type) ? b.action.type : 'bar', value: str(b.action && b.action.value, 400) }
    }))
  }));
  return { pages: pages.length ? pages : starter().pages };
}
function load() {
  if (cfg) return cfg;
  try { cfg = clean(JSON.parse(fs.readFileSync(file(), 'utf8'))); } catch { cfg = starter(); }
  return cfg;
}
function save(c) { cfg = clean(c); try { fs.mkdirSync(ctx.userData(), { recursive: true }); fs.writeFileSync(file(), JSON.stringify(cfg)); } catch {} return cfg; }

/** Telefona giden düzen: eylem bilgisi (yol, komut) içermez. Uygulama tuşlarında simge boşsa uygulamanın kendi simgesi (küçük PNG) eklenir. */
async function layout() {
  const pages = [];
  for (const p of load().pages) {
    const buttons = [];
    for (const b of p.buttons) {
      const o = { id: b.id, label: b.label, icon: b.icon, color: b.color };
      if (b.action.type === 'app' && !b.icon && b.action.value) { const img = await ctx.iconOf(b.action.value); if (img) o.img = img; }
      buttons.push(o);
    }
    pages.push({ id: p.id, name: p.name, buttons });
  }
  pages.push({ id: 'power', name: 'Güç', buttons: POWER.map((b) => ({ id: b.id, label: b.label, icon: b.icon, color: b.color, confirm: b.confirm || '' })) });
  return { pages };
}

// Kalıcı PowerShell: tuş basımı için user32!keybd_event (her basışta yeni süreç açmamak için)
function keysHelper() {
  if (helper && !helper.killed) return helper;
  const script = "$ErrorActionPreference='SilentlyContinue'; Add-Type -Namespace W -Name K -MemberDefinition '[DllImport(\"user32.dll\")] public static extern void keybd_event(byte v, byte s, int f, int e);'; while(($l=[Console]::In.ReadLine()) -ne $null){ $p=$l.Split(' '); if($p.Length -eq 3){ [W.K]::keybd_event([byte][int]$p[1],0,[int]$p[2],0) } }";
  helper = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { windowsHide: true, stdio: ['pipe', 'ignore', 'ignore'] });
  helper.on('exit', () => { helper = null; });
  return helper;
}
function sendKeys(vks) {
  const h = keysHelper(), line = (vk, up) => `K ${vk} ${(EXT.has(vk) ? 1 : 0) | (up ? 2 : 0)}\n`;
  h.stdin.write(vks.map((v) => line(v, false)).join('') + [...vks].reverse().map((v) => line(v, true)).join(''));
}
function parseKeys(s) {
  const parts = String(s).toLowerCase().split('+').map((x) => x.trim()).filter(Boolean), out = parts.map((p) => VK[p]);
  return parts.length && out.every(Number.isInteger) ? out : null;
}
const isWeb = (u) => { try { return ['http:', 'https:'].includes(new URL(u).protocol); } catch { return false; } };

/** Tuşa basıldı. Dönüş: {ok, error?} */
async function press(id) {
  const pw = POWER.find((x) => x.id === id);
  if (pw) {
    try {
      if (pw.screenOff) spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', "Add-Type -Namespace W -Name M -MemberDefinition '[DllImport(\"user32.dll\")] public static extern int SendMessage(int h,int m,int w,int l);'; [W.M]::SendMessage(0xffff,0x112,0xF170,2)"], { windowsHide: true, detached: true, stdio: 'ignore' }).unref();
      else spawn(pw.run[0], pw.run[1], { windowsHide: true, detached: true, stdio: 'ignore' }).unref();
      ctx.log('deck', pw.label, 'power');
      return { ok: true };
    } catch { return { ok: false, error: 'Çalıştırılamadı' }; }
  }
  const b = load().pages.flatMap((p) => p.buttons).find((x) => x.id === id);
  if (!b) return { ok: false, error: 'Tuş bulunamadı' };
  const { type, value } = b.action;
  try {
    if (type === 'url') { if (!isWeb(value)) return { ok: false, error: 'Geçersiz adres' }; ctx.openUrl(value); }
    else if (type === 'app') { if (!value) return { ok: false, error: 'Yol boş' }; const e = await ctx.openPath(value); if (e) return { ok: false, error: 'Açılamadı' }; }
    else if (type === 'keys') { const v = parseKeys(value); if (!v) return { ok: false, error: 'Kısayol anlaşılamadı' }; sendKeys(v); }
    else if (type === 'media') { const vk = MEDIA[value]; if (!vk) return { ok: false, error: 'Bilinmeyen medya tuşu' }; sendKeys([vk]); }
    else if (type === 'bar') ctx.bar(b.label, value);
    else if (type === 'mirror') ctx.mirrorToggle();
    else if (type === 'lock') spawn('rundll32.exe', ['user32.dll,LockWorkStation'], { windowsHide: true, detached: true, stdio: 'ignore' }).unref();
    else if (type === 'cmd') {
      if (!ctx.allowCmd()) return { ok: false, error: 'Komut çalıştırma kapalı (Phoneix → Deck)' };
      spawn('cmd.exe', ['/c', value], { windowsHide: true, detached: true, stdio: 'ignore' }).unref();
    }
    ctx.log('deck', b.label || id, type);
    return { ok: true };
  } catch (e) { return { ok: false, error: 'Çalıştırılamadı' }; }
}
function stop() { if (helper) { try { helper.kill(); } catch {} helper = null; } }

module.exports = { init: (c) => { ctx = { ...ctx, ...c }; }, get: () => load(), save, layout, press, stop, parseKeys, media: (n) => { const vk = MEDIA[n]; if (vk) sendKeys([vk]); } };
