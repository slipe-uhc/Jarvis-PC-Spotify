const { app, BrowserWindow, Tray, Menu, nativeImage, shell, ipcMain, session, powerSaveBlocker } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn, execFile } = require('child_process');

let win = null, tray = null, ps = null, quitting = false, wantVoice = false;
app.commandLine.appendSwitch('disable-renderer-backgrounding');
app.commandLine.appendSwitch('disable-background-timer-throttling');
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

// Script do Windows (System.Speech): reconhece fala e imprime "HEARD:texto"
const PS = [
  '[Console]::OutputEncoding=[System.Text.Encoding]::UTF8',
  'Add-Type -AssemblyName System.Speech',
  '$e=$null',
  "foreach($n in @('pt-BR','pt-PT','en-US')){",
  '  try{ $ci=New-Object System.Globalization.CultureInfo($n); $e=New-Object System.Speech.Recognition.SpeechRecognitionEngine($ci); break }catch{}',
  '}',
  'if(-not $e){ $e=New-Object System.Speech.Recognition.SpeechRecognitionEngine }',
  '$e.SetInputToDefaultAudioDevice()',
  '$e.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))',
  'Register-ObjectEvent -InputObject $e -EventName SpeechRecognized -Action { [Console]::Out.WriteLine("HEARD:" + $Event.SourceEventArgs.Result.Text); [Console]::Out.Flush() } | Out-Null',
  '$e.RecognizeAsync([System.Speech.Recognition.RecognizeMode]::Multiple)',
  '[Console]::Out.WriteLine("READY " + $e.RecognizerInfo.Culture.Name); [Console]::Out.Flush()',
  'while($true){ Start-Sleep -Seconds 1 }'
].join('\r\n');

function send(ch, v) { if (win && !win.isDestroyed()) win.webContents.send(ch, v); }

function startSpeech() {
  if (ps) return;
  const f = path.join(app.getPath('userData'), 'speech.ps1');
  fs.writeFileSync(f, PS, 'utf8');
  ps = spawn('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', f], { windowsHide: true });
  let buf = '';
  ps.stdout.setEncoding('utf8');
  ps.stdout.on('data', (d) => {
    buf += d;
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const l = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (l.startsWith('HEARD:')) send('heard', l.slice(6));
      else if (l) send('status', l);
    }
  });
  ps.stderr.on('data', (d) => send('status', 'ERRO de voz: ' + String(d).slice(0, 140)));
  ps.on('exit', () => {
    ps = null;
    if (wantVoice && !quitting) setTimeout(startSpeech, 3000); // guardião: religa a escuta sozinho
  });
}
function stopSpeech() { if (ps) { try { ps.kill(); } catch (e) {} ps = null; } }

// ---- abrir qualquer app do Windows pelo nome ----
process.on('uncaughtException', () => {});
let startApps = null;
const norm = (s) => String(s).toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim();
const ALIAS = { zap: 'whatsapp', zapzap: 'whatsapp', insta: 'instagram', yt: 'youtube', config: 'configuracoes', ajustes: 'configuracoes' };
function loadStartApps(cb) {
  if (startApps) return cb(startApps);
  execFile('powershell.exe', ['-NoProfile', '-Command', '[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; Get-StartApps | ConvertTo-Json -Compress'],
    { windowsHide: true, maxBuffer: 8 * 1024 * 1024 }, (err, out) => {
      try {
        let j = JSON.parse(out);
        if (!Array.isArray(j)) j = [j];
        startApps = j.map((a) => ({ n: a.Name, id: a.AppID }));
      } catch (e) { startApps = []; }
      cb(startApps);
    });
}
const PROTO = { spotify: 'spotify:', discord: 'discord:', steam: 'steam:', whatsapp: 'whatsapp:', telegram: 'tg:', slack: 'slack:', configuracoes: 'ms-settings:', calculadora: 'calculator:', loja: 'ms-windows-store:' };
function webFallback(name, url) {
  try {
    if (url && /^https?:/i.test(url)) shell.openExternal(url);
    else shell.openExternal('https://www.google.com/search?q=' + encodeURIComponent(name));
  } catch (e) {}
}
function launch(cmd, args, fb) {
  try {
    const c = spawn(cmd, args, { detached: true, stdio: 'ignore' });
    c.on('error', fb);
    c.unref();
  } catch (e) { fb(); }
}
function openApp(name, url) {
  try {
    if (/^spotify:/i.test(url || '')) {
      const qs = decodeURIComponent(url.replace(/^spotify:search:/i, ''));
      shell.openExternal(url).catch(() => webFallback(name, qs ? 'https://open.spotify.com/search/' + encodeURIComponent(qs) : 'https://open.spotify.com'));
      return;
    }
    const q0 = norm(name).replace(/^(o|a|os|as|app|aplicativo|site)\s+/, '');
    const q = ALIAS[q0] || q0;
    const web = (url && /^https?:/i.test(url)) ? url : (SITE_BY[q] || '');
    if (PROTO[q]) {
      shell.openExternal(PROTO[q]).catch(() => webFallback(name, web));
      return;
    }
    loadStartApps((list) => {
      let best = null, bs = 0;
      for (const a of list) {
        const l = norm(a.n);
        if (!l) continue;
        let sc = 0;
        if (l === q) sc = 100; else if (l.startsWith(q)) sc = 80; else if (l.includes(q)) sc = 60; else if (q.includes(l) && l.length >= 3) sc = 50;
        if (sc > bs) { bs = sc; best = a; }
      }
      if (best && bs >= 50 && q) {
        const id = String(best.id || '');
        if (/^[a-z]:\\/i.test(id) && /\.exe$/i.test(id)) launch(id, [], () => webFallback(name, web));
        else launch('explorer.exe', ['shell:AppsFolder\\' + id], () => webFallback(name, web));
        return;
      }
      webFallback(name, web);
    });
  } catch (e) { webFallback(name, url); }
}
const SITE_BY = { youtube: 'https://www.youtube.com', github: 'https://github.com', instagram: 'https://www.instagram.com', tiktok: 'https://www.tiktok.com', netflix: 'https://www.netflix.com', gmail: 'https://mail.google.com', chatgpt: 'https://chatgpt.com', canva: 'https://www.canva.com', capcut: 'https://www.capcut.com', twitch: 'https://www.twitch.tv', reddit: 'https://www.reddit.com' };

function show() { if (win) { win.show(); win.focus(); } }

function createWindow() {
  win = new BrowserWindow({
    width: 1200, height: 820, backgroundColor: '#000000', autoHideMenuBar: true, title: 'Jarvis',
    icon: path.join(__dirname, 'icon.png'),
    show: !process.argv.includes('--hidden'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, backgroundThrottling: false, autoplayPolicy: 'no-user-gesture-required' }
  });
  win.loadFile(path.join(__dirname, 'app', 'index.html'));
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file:')) { e.preventDefault(); shell.openExternal(url); } });
  win.on('close', (e) => { if (!quitting) { e.preventDefault(); win.hide(); } });
}

const lock = app.requestSingleInstanceLock();
if (!lock) {
  app.quit();
} else {
  app.on('second-instance', show);
  app.whenReady().then(() => {
    session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(true));
    createWindow();
    tray = new Tray(nativeImage.createFromPath(path.join(__dirname, 'icon.png')).resize({ width: 16, height: 16 }));
    tray.setToolTip('Jarvis');
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: 'Abrir Jarvis', click: show },
      { label: 'Sair', click: () => { quitting = true; stopSpeech(); app.quit(); } }
    ]));
    tray.on('click', show);
    try { app.setLoginItemSettings({ openAtLogin: true, args: ['--hidden'] }); } catch (e) {}
    powerSaveBlocker.start('prevent-app-suspension');
    ipcMain.on('voice:start', () => { wantVoice = true; startSpeech(); });
    ipcMain.on('voice:stop', () => { wantVoice = false; stopSpeech(); });
    ipcMain.on('open', (e, u) => { if (/^https?:/i.test(u)) shell.openExternal(u); });
    ipcMain.on('openApp', (e, name, url) => { openApp(String(name || ''), String(url || '')); });
    setTimeout(() => loadStartApps(() => {}), 4000);
  });
  app.on('before-quit', () => { quitting = true; stopSpeech(); });
  app.on('window-all-closed', () => {});
}
