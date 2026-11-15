const { app, BrowserWindow, nativeTheme } = require('electron');
const fs = require('fs');
const path = require('path');
const src = process.argv[process.argv.length - 2];
const out = process.argv[process.argv.length - 1];
const tabs = ['home', 'modes', 'files', 'photos', 'screen', 'settings'];
const stub = `<script>
let st={paired:true,name:'Pixel 8',lastMsg:Date.now()-5000,phone:{t:Date.now(),battery:76,charging:true},dynbar:true,activity:[{t:Date.now()-60000,text:'Bildirim: WhatsApp'},{t:Date.now()-900000,text:'Pano telefona gönderildi'}],transfers:[{dir:'out',name:'rapor.pdf',size:100,done:40,state:'active'}],replies:[{id:'1',app:'WhatsApp',title:'Ali',text:'Akşam yemeğe geliyor musun?'}],mirror:{installed:true,devices:[{model:'Pixel 8',wifi:true}]},apk:{},saveDir:'Downloads/Phoneix',ips:['192.168.1.5'],port:8765,listening:true,ringSound:'app'};
const dk={pages:[{id:'p1',name:'Ana',buttons:[{id:'a',label:'Ses',icon:'🔊',color:'#2563eb',action:{type:'media',value:'mute'}},{id:'b',label:'Kilitle',icon:'🔒',color:'#7c5cff',action:{type:'lock',value:''}}]}]};
let listener=()=>{};
window.phoneix=new Proxy({onState:(fn)=>{listener=fn},onPaired:()=>{},state:async()=>st,deckGet:async()=>dk,sensorStatus:async()=>({}),deckApps:async()=>[]},{get:(t,k)=>k in t?t[k]:async()=>({ok:true,items:[]})});
window.__shotSet=(paired)=>{st={...st,paired};listener(st)};
</script>`;
app.disableHardwareAcceleration();
app.setPath('userData', path.join(path.dirname(out), '.electron-shot-profile'));
const log=(m)=>fs.appendFileSync(out+'.log',new Date().toISOString()+' '+m+'\n');
log('boot '+src);
app.whenReady().then(async()=>{
  log('ready');
  fs.mkdirSync(path.dirname(out),{recursive:true});
  let html=fs.readFileSync(src,'utf8').replace('<script>\n(() =>',stub+'<script>\n(() =>');
  const test=src+'.test.html';fs.writeFileSync(test,html);
  const w=new BrowserWindow({width:+(process.env.W||980),height:+(process.env.H||680),show:false,backgroundColor:'#0b1220'});
  w.webContents.on('console-message',(e,l,m,li)=>console.log('CONSOLE',m,li));
  w.webContents.on('did-fail-load',(e,c,d)=>console.log('FAIL',d));
  await w.loadFile(test); await w.webContents.insertCSS('*,*::before,*::after{animation:none!important;transition:none!important}'); log('loaded '+test);
  for(const theme of ['dark','light']) for(const paired of [true,false]) {
    nativeTheme.themeSource=theme;
    await w.webContents.executeJavaScript(`window.__shotSet(${paired})`);
    await new Promise(r=>setTimeout(r,500));
    const list=paired?tabs:['home','screen','settings'];
    for(const t of list){
      await w.webContents.executeJavaScript(`document.querySelector('.nb[data-t=${t}]').click()`);
      await new Promise(r=>setTimeout(r,1000));
      const image=await w.webContents.capturePage();
      fs.writeFileSync(out+'-'+theme+'-'+(paired?'paired':'unpaired')+'-'+t+'.png',image.toPNG()); log('saved '+theme+' '+paired+' '+t);
    }
  }
  fs.unlinkSync(test);log('complete');app.quit();
});
