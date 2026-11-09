'use strict';
const term = new Terminal({
  fontSize: 12, fontFamily: 'monospace', cursorBlink: true, cursorStyle:'bar',cursorInactiveStyle:'bar',cursorWidth:1,scrollback: 2000,
  allowProposedApi: false, screenReaderMode: false,
  theme: {background:'#172021',foreground:'#edf3ef',cursor:'#8fe0c4',selectionBackground:'#436459'},
  windowOptions: {getWinSizeChars:true}
});
const fit = new FitAddon.FitAddon();
const serialize = new SerializeAddon.SerializeAddon();
term.loadAddon(fit);term.loadAddon(serialize);term.open(document.getElementById('terminal'));
term.textarea.setAttribute('autocomplete','off');
term.textarea.setAttribute('autocorrect','off');
term.textarea.setAttribute('autocapitalize','off');
term.textarea.setAttribute('inputmode','none');
term.textarea.spellcheck=false;
// Keep a narrow insertion cursor even when a CLI requests a block cursor.
term.parser.registerCsiHandler({intermediates:' ',final:'q'},()=>{term.options.cursorStyle='bar';return true;});
// Repeated cleanup must not restore an old saved cursor in the normal buffer.
term.parser.registerCsiHandler({prefix:'?',final:'l'},params=>params.length===1&&params[0]===1049&&term.buffer.active.type==='normal');
let replaying = false, offset = '0', saveTimer, inputTimer, resizeTimer, pendingInput = '';
let savingDirty=false,lastSaved=0,writing=false;
const updates=[];
function save(){
  clearTimeout(saveTimer);
  if(!savingDirty)return;
  if(writing||touching||flingFrame){saveTimer=setTimeout(save,1000);return;}
  PocketTerminal.snapshot(serialize.serialize({scrollback:2000}),offset,term.cols,term.rows);
  savingDirty=false;lastSaved=Date.now();
}
function scheduleSave(){
  savingDirty=true;clearTimeout(saveTimer);
  saveTimer=setTimeout(save,Math.min(1500,Math.max(0,5000-(Date.now()-lastSaved))));
}
function send(data){
  pendingInput+=data;clearTimeout(inputTimer);
  inputTimer=setTimeout(()=>{const value=pendingInput;pendingInput='';if(value)PocketTerminal.input(value);},16);
}
term.onData(data=>{if(!replaying)send(data);});
term.onResize(size=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>PocketTerminal.resize(size.cols,size.rows),150);});
function measure(){try{fit.fit();}catch(ignored){}}
new ResizeObserver(measure).observe(document.getElementById('terminal'));
window.addEventListener('pagehide',save);
// Android WebView needs touch scrolling for xterm's virtual (non-native) viewport.
// Keep sub-line motion between frames and add inertia without posting native calls.
const surface=document.getElementById('terminal');
let touching=false,dragged=false,startY=0,lastY=0,lastTime=0,velocity=0,remainder=0,flingFrame=0;
function stopFling(){cancelAnimationFrame(flingFrame);flingFrame=0;velocity=0;}
function scrollPixels(pixels){
  if(term.buffer.active.type==='alternate'||term.modes.mouseTrackingMode!=='none'){
    term.element.dispatchEvent(new WheelEvent('wheel',{deltaY:pixels,bubbles:true,cancelable:true}));return;
  }
  const lineHeight=term.element.querySelector('.xterm-screen').clientHeight/term.rows;
  remainder+=pixels/Math.max(1,lineHeight);
  const lines=Math.trunc(remainder);
  if(lines){term.scrollLines(lines);remainder-=lines;}
}
surface.addEventListener('touchstart',event=>{
  stopFling();if(event.touches.length!==1){touching=false;return;}
  touching=true;dragged=false;remainder=0;startY=lastY=event.touches[0].clientY;lastTime=performance.now();
},{passive:true});
surface.addEventListener('touchmove',event=>{
  if(!touching||event.touches.length!==1)return;
  const y=event.touches[0].clientY,now=performance.now(),delta=lastY-y,elapsed=now-lastTime;
  if(Math.abs(startY-y)>5)dragged=true;
  if(dragged){event.preventDefault();event.stopPropagation();scrollPixels(delta);velocity=.6*velocity+.4*delta/Math.max(8,elapsed);}
  lastY=y;lastTime=now;
},{passive:false});
surface.addEventListener('touchend',event=>{
  if(!touching)return;touching=false;
  if(!dragged)return;
  event.preventDefault();event.stopPropagation();
  if(performance.now()-lastTime>100)velocity=0;
  velocity=Math.max(-3,Math.min(3,velocity));let previous=performance.now();
  function coast(now){
    const elapsed=Math.min(32,now-previous);previous=now;
    scrollPixels(velocity*elapsed);velocity*=Math.exp(-elapsed/220);
    if(Math.abs(velocity)>.025)flingFrame=requestAnimationFrame(coast);else{flingFrame=0;save();}
  }
  flingFrame=requestAnimationFrame(coast);
},{passive:false});
surface.addEventListener('touchcancel',()=>{touching=false;stopFling();});
function renderNext(){
  if(writing||!updates.length)return;
  writing=true;const update=updates.shift();
  if(update.reset){
    stopFling();replaying=true;term.reset();
    if(update.initial&&update.cols>=2&&update.rows>=2)term.resize(update.cols,update.rows);
  }
  term.write(update.initial+update.data,()=>{
    offset=update.end;replaying=false;
    if(update.reset)measure();
    writing=false;scheduleSave();renderNext();
  });
}
window.pocket={
  render(update){
    updates.push(update);renderNext();
  },
  accessibility(enabled){term.options.screenReaderMode=enabled;},
  key(data){send(data);},
  paste(text){term.paste(text);},
  submit(text){term.paste(text);send('\r');},
  clear(){term.clear();scheduleSave();},
  copy(mode){
    if(term.hasSelection()){PocketTerminal.copy(term.getSelection());return;}
    const buffer=term.buffer.active, lines=[];
    const start=mode==='screen'?buffer.viewportY:0;
    const end=mode==='screen'?Math.min(buffer.length,start+term.rows):buffer.length;
    for(let row=start;row<end;row++){
      const line=buffer.getLine(row);if(!line)continue;
      const value=line.translateToString(true);
      if(line.isWrapped&&lines.length)lines[lines.length-1]+=value;else lines.push(value);
    }
    PocketTerminal.copy(lines.join('\n').replace(/\n+$/,''));
  },
  focus(){term.focus();}
};
measure();PocketTerminal.ready();
