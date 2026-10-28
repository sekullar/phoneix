// Bilgisayardan telefona anlık komutlar (pano, "telefonumu çaldır"). Telefon zaten uzun sorgu (poll) ile bağlı olduğu için komut
// kuyruğa girince poll hemen döner. Komutlar kısa ömürlüdür: telefon çevrimdışıyken biriken eski pano telefonu sonradan ezmesin.
const TTL = 30000;
let q = [], wakeFn = () => {}, tookFn = () => {};

const prune = () => { const n = Date.now(); q = q.filter((c) => n - c.t < TTL); };

module.exports = {
  onWake: (f) => { wakeFn = f; },
  onTake: (f) => { tookFn = f; },
  push(cmd) {
    prune();
    if (cmd.type === 'clip' || cmd.type === 'ring' || cmd.type === 'stopring' || cmd.type === 'mode') q = q.filter((c) => c.type !== cmd.type); // aynı türden yalnızca sonuncusu
    q.push({ ...cmd, t: Date.now() });
    wakeFn();
  },
  pending() { prune(); return q.length; },
  take() { prune(); const out = q.map(({ t, ...c }) => c); q = []; if (out.length) tookFn(out); return out; },
  clear() { q = []; }
};
