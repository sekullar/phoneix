// Sistem izleme: telefon "monitör modu"nda saniyede bir ister, biz de yalnızca istendiği sürece örnekleriz (istek kesilince ~8 sn sonra durur).
// CPU/RAM: Node (os). GPU: nvidia-smi varsa onunla (kullanım, sıcaklık, VRAM, güç); yoksa Windows GPU sayaçlarıyla (tüm üreticiler).
// Ağ: .NET arayüz sayaçları (tek bir kalıcı PowerShell süreci).
const os = require('os');
const { spawn, execFile } = require('child_process');

const IDLE_MS = 8000;
let timer = null, lastAsk = 0, snap = null, prevCpu = null, prevCores = null, ps = null, net = { rx: 0, tx: 0, t: 0, down: 0, up: 0 };
let gpuInfo = { name: '', nv: null }, cpuName = (os.cpus()[0] || {}).model || 'CPU';

const NV_ARGS = ['--query-gpu=name,utilization.gpu,temperature.gpu,memory.used,memory.total,power.draw,clocks.gr', '--format=csv,noheader,nounits'];
function nvidia() {
  return new Promise((res) => execFile('nvidia-smi', NV_ARGS, { timeout: 2500, windowsHide: true }, (err, out) => {
    if (err) return res(null);
    const p = String(out).trim().split(/\r?\n/)[0].split(',').map((s) => s.trim());
    const n = (i) => { const v = parseFloat(p[i]); return Number.isFinite(v) ? v : null; };
    res({ name: p[0], util: n(1), temp: n(2), vramUsed: n(3), vramTotal: n(4), power: n(5), clock: n(6) });
  }));
}

// Windows GPU sayaçları (nvidia-smi yoksa): en yoğun motor türünün toplamı
const PS_GPU = `$ErrorActionPreference='SilentlyContinue'; $c=(Get-Counter '\\GPU Engine(*)\\Utilization Percentage').CounterSamples; if($c){ $m=0; $c | Group-Object { ($_.InstanceName -split 'engtype_')[1] } | ForEach-Object { $s=($_.Group | Measure-Object CookedValue -Sum).Sum; if($s -gt $m){$m=$s} }; [math]::Min(100,[math]::Round($m)) }`;
let gpuPs = { util: null, busy: false };
function winGpu() {
  if (gpuPs.busy) return;
  gpuPs.busy = true;
  execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', PS_GPU], { timeout: 8000, windowsHide: true }, (err, out) => {
    gpuPs.busy = false; const v = parseFloat(String(out).trim()); gpuPs.util = Number.isFinite(v) ? v : null;
  });
}

function startNet() {
  if (ps) return;
  // ağ sayaçları + (varsa) CPU sıcaklığı: Windows yönetici izni olmadan CPU sıcaklığı vermez; LibreHardwareMonitor / OpenHardwareMonitor çalışıyorsa
  // onun WMI sensörlerinden okunur (ayrıca http://127.0.0.1:8085/data.json denenir). Yoksa -1 gönderilir.
  const script = "$ErrorActionPreference='SilentlyContinue'; function T{ foreach($ns in 'root/LibreHardwareMonitor','root/OpenHardwareMonitor'){ $x=Get-CimInstance -Namespace $ns -ClassName Sensor | Where-Object { $_.SensorType -eq 'Temperature' -and $_.Identifier -match 'cpu' }; if($x){ $p=$x | Where-Object { $_.Name -match 'Package|Tctl|Tdie|CPU' } | Select-Object -First 1; if(-not $p){ $p=$x | Sort-Object Value -Descending | Select-Object -First 1 }; return [math]::Round($p.Value,1) } }; return -1 }; $i=0; $t=-1; while($true){ $r=0;$s=0; foreach($n in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()){ if($n.OperationalStatus -eq 'Up' -and $n.NetworkInterfaceType -ne 'Loopback'){ $q=$n.GetIPv4Statistics(); $r+=$q.BytesReceived; $s+=$q.BytesSent } }; if($i % 3 -eq 0){ $t=T }; $i++; [Console]::Out.WriteLine(\"$r $s $t\"); [Console]::Out.Flush(); Start-Sleep -Milliseconds 1000 }";
  ps = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] });
  let buf = '';
  ps.stdout.on('data', (d) => {
    buf += d; let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const [rx, tx, ct] = buf.slice(0, i).trim().split(' ').map((v) => Number(String(v).replace(',', '.'))); buf = buf.slice(i + 1);
      if (!Number.isFinite(rx) || !Number.isFinite(tx)) continue;
      net.cpuTemp = Number.isFinite(ct) && ct > 0 && ct < 130 ? ct : null;
      const now = Date.now();
      if (net.t) { const dt = (now - net.t) / 1000; net.down = Math.max(0, (rx - net.rx) / dt); net.up = Math.max(0, (tx - net.tx) / dt); }
      net.rx = rx; net.tx = tx; net.t = now;
    }
  });
  ps.on('exit', () => { ps = null; });
}

function cpuSample() {
  const cs = os.cpus(), cores = cs.map((c) => [c.times.idle, Object.values(c.times).reduce((a, b) => a + b, 0)]);
  let out = { cpu: 0, cores: cs.map(() => 0) };
  if (prevCores && prevCores.length === cores.length) {
    let di = 0, dt = 0;
    out.cores = cores.map((c, i) => { const a = c[0] - prevCores[i][0], b = c[1] - prevCores[i][1]; di += a; dt += b; return b > 0 ? Math.round(100 * (1 - a / b)) : 0; });
    out.cpu = dt > 0 ? Math.round(100 * (1 - di / dt)) : 0;
  }
  prevCores = cores; return out;
}

async function tick() {
  if (Date.now() - lastAsk > IDLE_MS) return stop();
  const cpu = cpuSample(), nv = await nvidia();
  if (!nv) winGpu();
  if (nv) gpuInfo.name = nv.name;
  const total = os.totalmem(), used = total - os.freemem();
  snap = {
    t: Date.now(), cpuName, cpu: cpu.cpu, cores: cpu.cores,
    ram: { used, total },
    gpu: nv ? { name: nv.name, util: nv.util, temp: nv.temp, vramUsed: nv.vramUsed, vramTotal: nv.vramTotal, power: nv.power, clock: nv.clock }
            : { name: 'GPU', util: gpuPs.util, temp: null, vramUsed: null, vramTotal: null, power: null, clock: null },
    cpuTemp: net.cpuTemp == null ? null : net.cpuTemp,
    net: { down: Math.round(net.down), up: Math.round(net.up) }
  };
}

function ensure() {
  lastAsk = Date.now();
  if (timer) return;
  prevCores = null; startNet(); cpuSample(); tick();
  timer = setInterval(tick, 1000);
}
function stop() { if (timer) { clearInterval(timer); timer = null; } if (ps) { try { ps.kill(); } catch {} ps = null; } net = { rx: 0, tx: 0, t: 0, down: 0, up: 0, cpuTemp: null }; snap = null; }

/** En son örnek; ilk çağrıda örnekleme başlar ve kısa süre bekler. */
async function get() {
  ensure();
  if (!snap) { for (let i = 0; i < 24 && !snap; i++) await new Promise((r) => setTimeout(r, 150)); }
  return snap;
}
module.exports = { get, stop, active: () => !!timer };
