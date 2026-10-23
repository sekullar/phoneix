// Phoneix sunucusu: telefondan (Android uygulaması) gelen şifreli olayları alır. Yerel ağda dinler (0.0.0.0), yalnızca eşleştirme
// anahtarıyla şifrelenmiş istekleri kabul eder. Ayrıntı: ../PROTOCOL.md
//
// İstek: POST /phoneix/v1, gövde = base64( nonce(12) | şifreli metin | etiket(16) ), AES-256-GCM, AAD = "phoneix1".
// Düz metin: {"t": ms, "type": "hello"|"test"|..., ...}. Yanıt aynı biçimde şifrelidir; telefon da bilgisayarı böyle doğrular.
// Anahtar yanlış, zaman 60 sn dışında veya nonce tekrar ise yanıt 404 (anahtarı bilmeyen bir şey öğrenemez).
const http = require('http'), crypto = require('crypto'), os = require('os');
const AAD = Buffer.from('phoneix1'), PORT = 47810, WINDOW = 60000;
let ctx, server = null, port = 0, pending = null, seen = new Map(), hits = [], lastSeen = 0, lastIp = '';

const key = () => { const k = ctx.getKey(); return k ? Buffer.from(k, 'base64') : null; };

function seal(k, obj) {
  const nonce = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', k, nonce);
  c.setAAD(AAD);
  const ct = Buffer.concat([c.update(JSON.stringify(obj), 'utf8'), c.final()]);
  return Buffer.concat([nonce, ct, c.getAuthTag()]).toString('base64');
}
function open(k, b64) {
  try {
    const b = Buffer.from(b64, 'base64'); if (b.length < 12 + 16 + 2 || !k || k.length !== 32) return null;
    const d = crypto.createDecipheriv('aes-256-gcm', k, b.subarray(0, 12));
    d.setAAD(AAD); d.setAuthTag(b.subarray(b.length - 16));
    return { nonce: b.subarray(0, 12).toString('hex'), msg: JSON.parse(Buffer.concat([d.update(b.subarray(12, b.length - 16)), d.final()]).toString('utf8')) };
  } catch { return null; }
}
const fresh = (nonce, t) => { // tekrar saldırısı: eski zaman damgası veya görülmüş nonce reddedilir
  const n = Date.now(); for (const [k, v] of seen) if (n - v > WINDOW * 2) seen.delete(k);
  if (typeof t !== 'number' || Math.abs(n - t) > WINDOW || seen.has(nonce)) return false;
  seen.set(nonce, n); return true;
};
const limited = () => { const n = Date.now(); while (hits.length && n - hits[0] > 5000) hits.shift(); if (hits.length >= 200) return true; hits.push(n); return false; };

function handle(req, res) {
  const miss = () => { res.statusCode = 404; res.end(); };
  if (req.method !== 'POST' || req.url !== '/phoneix/v1' || limited()) return miss();
  let body = '', size = 0;
  req.on('data', (d) => { size += d.length; if (size > 4 * 1024 * 1024) { miss(); req.destroy(); } else body += d; });
  let gone = false; res.on('close', () => { gone = true; });
  req.on('end', async () => {
    if (res.writableEnded) return;
    let k = key(), r = open(k, body), fromPending = false;
    if (!r && pending) { r = open(pending, body); if (r) { k = pending; fromPending = true; } }
    if (!r || !fresh(r.nonce, r.msg.t)) return miss();
    if (fromPending) { ctx.setKey(pending.toString('base64')); pending = null; } // ilk geçerli mesaj: eşleştirme tamam
    lastSeen = Date.now(); lastIp = String(req.socket.remoteAddress || '').replace(/^::ffff:/, '');
    let out = { ok: true, name: os.hostname() };
    try {
      const extra = await ctx.onMessage(r.msg, fromPending, () => gone);
      if (extra === null) return res.end(); // istemci uzun beklemeden vazgeçti
      out = { ...out, ...(extra || {}) };
    } catch { out.ok = false; }
    if (gone) return;
    res.statusCode = 200; res.setHeader('Content-Type', 'text/plain'); res.end(seal(k, { t: Date.now(), ...out }));
  });
}

// Eşleşmiş bir anahtar veya eşleştirme bekleyen anahtar varsa dinle; yoksa kapat
function sync() {
  if (!ctx) return;
  const want = !!(ctx.getKey() || pending);
  if (want && !server) {
    const s = http.createServer(handle), tryPort = (p) => {
      s.once('error', (e) => { if (p !== 0) tryPort(0); else ctx.log('dinlenemedi', e.message); });
      s.listen(p, '0.0.0.0', () => { server = s; port = s.address().port; ctx.changed && ctx.changed(); });
    };
    tryPort(PORT);
  } else if (!want && server) { const s = server; server = null; port = 0; try { s.close(); } catch {} ctx.changed && ctx.changed(); }
}

function ips() { // özel ağ adresleri (192.168.x.x, 10.x, 172.16-31.x); sanal bağdaştırıcılar sona
  const out = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) for (const a of list || []) {
    if (a.family !== 'IPv4' || a.internal || !/^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(a.address)) continue;
    out.push({ ip: a.address, virt: /vEthernet|VirtualBox|VMware|WSL|Hyper-V|Docker|Loopback/i.test(name) });
  }
  return out.sort((a, b) => a.virt - b.virt).map((x) => x.ip);
}

// Yeni eşleştirme: yeni anahtar bekleyen olarak tutulur, telefon ilk mesajı gönderince kalıcı olur (eski eşleştirme o ana kadar geçerli)
function pair() {
  pending = crypto.randomBytes(32); sync();
  return new Promise((resolve) => {
    const t0 = Date.now(), wait = setInterval(() => {
      if (port || Date.now() - t0 > 3000) { clearInterval(wait); resolve(); }
    }, 50);
  }).then(() => {
    const data = { v: 1, n: os.hostname(), h: ips(), p: port, k: pending.toString('base64') };
    const url = 'phoneix://pair?d=' + Buffer.from(JSON.stringify(data)).toString('base64url');
    const qr = require('qrcode-generator')(0, 'M'); qr.addData(url); qr.make();
    return { svg: qr.createSvgTag({ cellSize: 5, margin: 3, scalable: true }), ips: data.h, port, url };
  });
}
function cancelPair() { pending = null; sync(); }
const status = () => ({ listening: !!server, port, pairing: !!pending, lastSeen, lastIp, ips: ips() });
function destroy() { pending = null; if (server) { try { server.close(); } catch {} server = null; port = 0; } }

module.exports = {
  // c: {getKey(), setKey(b64), onMessage(msg, firstMessage) -> yanıt alanları, log, changed()}
  init: (c) => { ctx = c; sync(); },
  sync, pair, cancelPair, status, destroy,
  _seal: seal, _open: open
};
