// CPU sıcaklığı için sensör yardımcısı: Windows bu değeri yalnızca yönetici yetkisiyle verir. Kullanıcı isteyince LibreHardwareMonitor
// (açık kaynak) indirilir ve oturum açılışında yükseltilmiş yetkiyle (bir kez UAC onayıyla kurulan zamanlanmış görev) arka planda çalıştırılır;
// stats.js sıcaklığı onun WMI sensörlerinden okur. Phoneix'in kendisi yönetici olarak çalışmaz.
const { spawn, execFile } = require('child_process');
const fs = require('fs'), path = require('path');

const VERSION = 'v0.9.6';
const URL = `https://github.com/LibreHardwareMonitor/LibreHardwareMonitor/releases/download/${VERSION}/LibreHardwareMonitor.zip`;
const TASK = 'Phoneix Sensor Helper';
const PORT = 8085; // LibreHardwareMonitor'ün yerel web sunucusu (yalnızca 127.0.0.1); stats.js buradan okur
let ctx = { userData: () => '.', log: () => {} };
let busy = false;

const dir = () => path.join(ctx.userData(), 'lhm');
const exe = () => path.join(dir(), 'LibreHardwareMonitor.exe');
const ps = (script, cb) => execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script], { windowsHide: true, timeout: 20000 }, (e, out) => cb(e, String(out || '')));

function status() {
  return new Promise((res) => {
    const installed = fs.existsSync(exe());
    ps(`$t = Get-ScheduledTask -TaskName '${TASK}' -ErrorAction SilentlyContinue; $p = Get-Process LibreHardwareMonitor -ErrorAction SilentlyContinue; "$([int][bool]$t) $([int][bool]$p)"`, (e, out) => {
      const [task, running] = out.trim().split(' ');
      fetch(`http://127.0.0.1:${PORT}/data.json`, { signal: AbortSignal.timeout(1500) }).then((r) => r.ok).catch(() => false)
        .then((web) => res({ installed, task: task === '1', running: running === '1', web, busy }));
    });
  });
}

async function download() {
  const r = await fetch(URL, { signal: AbortSignal.timeout(120000) });
  if (!r.ok) throw new Error('İndirilemedi (' + r.status + ')');
  const buf = Buffer.from(await r.arrayBuffer());
  if (buf.length < 1e6 || buf.length > 6e7 || buf.readUInt16LE(0) !== 0x4b50) throw new Error('İndirilen dosya geçersiz'); // "PK" zip imzası
  fs.mkdirSync(dir(), { recursive: true });
  const zip = path.join(dir(), 'lhm.zip');
  fs.writeFileSync(zip, buf);
  await new Promise((res, rej) => ps(`Expand-Archive -LiteralPath '${zip}' -DestinationPath '${dir()}' -Force`, (e) => (e ? rej(new Error('Arşiv açılamadı')) : res())));
  try { fs.unlinkSync(zip); } catch {}
  if (!fs.existsSync(exe())) throw new Error('LibreHardwareMonitor.exe bulunamadı');
}

function writeConfig() { // sessiz başlasın (tepsiye küçülür), yalnızca 127.0.0.1'de web sunucusu açık olsun
  fs.writeFileSync(path.join(dir(), 'LibreHardwareMonitor.config'), '<?xml version="1.0" encoding="utf-8"?>\n<configuration><appSettings>' +
    [['startMinMenuItem', 'true'], ['minTrayMenuItem', 'true'], ['minCloseMenuItem', 'true'], ['runWebServerMenuItem', 'true'], ['listenerIp', '127.0.0.1'], ['listenerPort', String(PORT)]]
      .map(([k, v]) => `<add key="${k}" value="${v}" />`).join('') + '</appSettings></configuration>\n');
}

/** Yükseltilmiş PowerShell çalıştırır (UAC penceresi çıkar); betik dosyası yazılır, çıkış kodu döner. */
function elevated(script) {
  const file = path.join(dir(), 'elevated.ps1');
  fs.writeFileSync(file, script, 'utf8');
  return new Promise((res) => {
    const c = spawn('powershell.exe', ['-NoProfile', '-Command', `try { $p = Start-Process powershell.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','${file}'; exit $p.ExitCode } catch { exit 99 }`], { windowsHide: true });
    c.on('exit', (code) => { try { fs.unlinkSync(file); } catch {} res(code); });
    c.on('error', () => res(99));
  });
}

/** İndir + oturum açılışı görevini kur + hemen başlat. Tek UAC onayı ister. */
async function setup() {
  if (busy) return { ok: false, error: 'Zaten sürüyor' };
  busy = true;
  try {
    if (!fs.existsSync(exe())) await download();
    writeConfig();
    const e = exe().replace(/'/g, "''"), d = dir().replace(/'/g, "''");
    const code = await elevated(`$ErrorActionPreference='Stop'
Stop-ScheduledTask -TaskName '${TASK}' -ErrorAction SilentlyContinue
Get-Process LibreHardwareMonitor -ErrorAction SilentlyContinue | Stop-Process -Force
$a = New-ScheduledTaskAction -Execute '${e}' -WorkingDirectory '${d}'
$t = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\\$env:USERNAME"
$pr = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\\$env:USERNAME" -LogonType Interactive -RunLevel Highest
$s = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable
Register-ScheduledTask -TaskName '${TASK}' -Action $a -Trigger $t -Principal $pr -Settings $s -Force | Out-Null
Start-ScheduledTask -TaskName '${TASK}'
`);
    if (code !== 0) return { ok: false, error: code === 99 ? 'Yönetici izni verilmedi' : 'Görev kurulamadı (kod ' + code + ')' };
    return { ok: true };
  } catch (e) { ctx.log('sensor setup: ' + e.message); return { ok: false, error: e.message }; }
  finally { busy = false; }
}

/** Görevi ve çalışan yardımcıyı kaldırır (UAC ister); indirilen dosyalar da silinir. */
async function remove() {
  if (busy) return { ok: false, error: 'Zaten sürüyor' };
  busy = true;
  try {
    fs.mkdirSync(dir(), { recursive: true });
    const code = await elevated(`Stop-ScheduledTask -TaskName '${TASK}' -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName '${TASK}' -Confirm:$false -ErrorAction SilentlyContinue
Get-Process LibreHardwareMonitor -ErrorAction SilentlyContinue | Stop-Process -Force
`);
    if (code !== 0) return { ok: false, error: code === 99 ? 'Yönetici izni verilmedi' : 'Kaldırılamadı (kod ' + code + ')' };
    try { fs.rmSync(dir(), { recursive: true, force: true }); } catch {}
    return { ok: true };
  } finally { busy = false; }
}

module.exports = { init: (c) => { ctx = Object.assign(ctx, c); }, status, setup, remove };
