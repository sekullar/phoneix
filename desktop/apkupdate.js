// Telefon uygulaması (APK) güncelleme: masaüstü uygulamasıyla birlikte gelen en yeni Phoneix-x.y.z.apk bulunur; telefon eskiyse
// kablosuz hata ayıklama (adb) üzerinden `adb install -r` ile kurulur. Telefon kendi sürümünü her `poll` içinde bildirir (PROTOCOL.md).
const fs = require('fs'), path = require('path');

const VER = /^\d+\.\d+\.\d+$/;
const parse = (v) => String(v).split('.').map((n) => +n || 0);
// a < b ise negatif
function cmp(a, b) { const x = parse(a), y = parse(b); for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] - y[i]; return 0; }

// Paketlenmiş uygulamada resources/apk, kaynaktan çalışırken ../releases
function dirs() {
  const d = [path.join(__dirname, '..', 'releases')];
  if (process.resourcesPath) d.unshift(path.join(process.resourcesPath, 'apk'));
  return d;
}
function bundled() {
  let best = null;
  for (const d of dirs()) {
    let names = []; try { names = fs.readdirSync(d); } catch { continue; }
    for (const n of names) {
      const m = /^Phoneix-(\d+\.\d+\.\d+)\.apk$/.exec(n);
      if (m && (!best || cmp(m[1], best.version) > 0)) best = { version: m[1], file: path.join(d, n) };
    }
  }
  return best;
}
// Telefon bu sürümden eskiyse true. Telefon sürüm bildirmiyorsa (0.3.0 öncesi uygulama) eski sayılır.
const outdated = (phoneVer, b) => !!b && VER.test(b.version) && cmp(VER.test(phoneVer || '') ? phoneVer : '0.0.0', b.version) < 0;

module.exports = { bundled, outdated, cmp, VER };
