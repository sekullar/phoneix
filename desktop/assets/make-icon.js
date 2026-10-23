// Tek seferlik: assets/icon.png (256) ve assets/tray.png (32) üretir. Çalıştır: node assets/make-icon.js
const zlib = require('zlib'), fs = require('fs'), path = require('path');

function png(size, pixel) {
  const raw = Buffer.alloc((size * 4 + 1) * size);
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0;
    for (let x = 0; x < size; x++) { const [r, g, b, a] = pixel(x + 0.5, y + 0.5, size); raw.set([r, g, b, a], y * (size * 4 + 1) + 1 + x * 4); }
  }
  const crcT = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (b) => { let c = 0xffffffff; for (const x of b) c = crcT[(c ^ x) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
// Yuvarlatılmış dikdörtgen işaretli uzaklık (negatif = içeride)
const rr = (px, py, cx, cy, hw, hh, r) => { const dx = Math.abs(px - cx) - (hw - r), dy = Math.abs(py - cy) - (hh - r); return Math.hypot(Math.max(dx, 0), Math.max(dy, 0)) + Math.min(Math.max(dx, dy), 0) - r; };
const cover = (d) => Math.max(0, Math.min(1, 0.5 - d)); // 1 px kenar yumuşatma
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));

// Uygulama simgesi: pembe-mor gradyan kare içinde beyaz telefon, ekranda bildirim noktası
function icon(x, y, s) {
  const u = s / 256, bg = rr(x, y, s / 2, s / 2, s / 2 - 8 * u, s / 2 - 8 * u, 56 * u);
  const t = (x + y) / (2 * s), base = mix([255, 94, 150], [138, 92, 246], t);
  let c = [...base, 255 * cover(bg / 1)];
  const ph = rr(x, y, s / 2, s / 2, 54 * u, 88 * u, 18 * u), scr = rr(x, y, s / 2, s / 2 - 4 * u, 44 * u, 72 * u, 9 * u);
  const dot = Math.hypot(x - (s / 2 + 22 * u), y - (s / 2 - 52 * u)) - 11 * u;
  const white = cover(ph), hole = cover(scr), dotc = cover(dot);
  const a = c[3];
  let col = c.slice(0, 3);
  col = mix(col, [255, 255, 255], white);
  col = mix(col, mix(base, [60, 30, 110], 0.55), hole * white);
  col = mix(col, [255, 255, 255], dotc * hole);
  return [...col, a];
}
// Tepsi simgesi: küçük boyutta okunsun diye sade beyaz telefon, saydam zemin
function tray(x, y, s) {
  const u = s / 32, ph = rr(x, y, s / 2, s / 2, 8 * u, 13 * u, 3 * u), scr = rr(x, y, s / 2, s / 2 - 1 * u, 5.5 * u, 9.5 * u, 1.5 * u);
  const a = Math.round(255 * Math.max(cover(ph) - cover(scr) * 0.65, 0));
  return [255, 255, 255, a];
}
fs.writeFileSync(path.join(__dirname, 'icon.png'), png(256, icon));
fs.writeFileSync(path.join(__dirname, 'tray.png'), png(32, tray));
console.log('ok');
