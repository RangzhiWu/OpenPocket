const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const path=require('node:path');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
 try{
  const page=await browser.newPage({viewport:{width:390,height:600},hasTouch:true,isMobile:true,deviceScaleFactor:2});
  await page.addInitScript(()=>{
   window.copied='';window.saved=null;window.inputs=[];
   window.PocketTerminal={ready(){},resize(){},input(value){window.inputs.push(value);},copy(value){window.copied=value;},snapshot(state,end,cols,rows){window.saved={state,end,cols,rows};}};
  });
  await page.goto(require('node:url').pathToFileURL(path.resolve(__dirname,'../android-client/app/src/main/assets/terminal/index.html')).href);
  await page.waitForFunction(()=>window.pocket&&term.cols>2);
  async function render(data,reset=false,initial='',cols=0,rows=0){
   await page.evaluate(({data,reset,initial,cols,rows})=>pocket.render({data,reset,initial,cols,rows,end:String(Number(offset)+data.length)}),{data,reset,initial,cols,rows});
   await page.waitForFunction(()=>!writing&&!updates.length);
  }
  const lines=Array.from({length:1500},(_,i)=>'history '+String(i).padStart(4,'0')+' 中文').join('\r\n')+'\r\n';
  await render(lines,true);
  const before=await page.evaluate(()=>term.buffer.active.viewportY);
  const cdp=await page.context().newCDPSession(page);
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:170,y:200}]});
  for(let y=220;y<=460;y+=20){await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:170,y}]});await page.waitForTimeout(16);}
  await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  const dragged=await page.evaluate(()=>term.buffer.active.viewportY);
  await page.waitForTimeout(350);
  const coasted=await page.evaluate(()=>term.buffer.active.viewportY);
  assert(dragged<before-5,'touch drag scrolls the virtual viewport');
  assert(coasted<dragged,'release continues inertia');
  await render('new output while reading\r\n');
  const after=await page.evaluate(()=>term.buffer.active.viewportY);
  assert(after<before-5,'new output preserves historical reading position');
  await page.evaluate(()=>pocket.copy('history'));
  assert((await page.evaluate(()=>copied)).includes('history 0000 中文'));
  console.log('PASS: 1500 Unicode lines, touch drag, inertia, history position, plain-text copy');
  await render('\x1b[?1049h\x1b[2J\x1b[HCODEX MENU\x1b[4;4H/model\x1b[2 q');
  assert.equal(await page.evaluate(()=>term.options.cursorStyle),'bar');
  assert.equal(await page.evaluate(()=>term.buffer.active.type),'alternate');
  await render('\x1b[?1049l\r\nAFTER_CODEX\r\n');
  assert.equal(await page.evaluate(()=>term.buffer.active.type),'normal');
  await page.evaluate(()=>pocket.copy('history'));
  assert(!(await page.evaluate(()=>copied)).includes('CODEX MENU'));
  assert((await page.evaluate(()=>copied)).includes('AFTER_CODEX'));
  console.log('PASS: alternate-screen exit restores shell buffer; CLI block cursor stays thin');
  await render('\x1b[?1049h\x1b[4;8r\x1b[?25lSTALE_MENU\x1b[?1049l\x1b[!p\x1b[0m\x1b[?25h\r\n\x1b[2K[shell ended]\r\n');
  await page.evaluate(()=>pocket.copy('history'));
  assert.equal(await page.evaluate(()=>term.buffer.active.type),'normal');
  assert((await page.evaluate(()=>copied)).includes('[shell ended]'));
  assert(!(await page.evaluate(()=>copied)).includes('STALE_MENU'));
  console.log('PASS: exited raw/alternate-screen program resets before its native notice');
  const stored=await page.evaluate(()=>({state:serialize.serialize({scrollback:2000}),cols:term.cols,rows:term.rows}));
  await page.setViewportSize({width:470,height:420});
  await render('',true,stored.state,stored.cols,stored.rows);
  await page.evaluate(()=>pocket.copy('history'));
  const restored=await page.evaluate(()=>copied);
  assert(restored.includes('AFTER_CODEX'));assert(!restored.includes('CODEX MENU'));
  console.log('PASS: restore at saved dimensions then resize preserves normal screen');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
