const fs=require('fs'),path=require('path'),zlib=require('zlib');
const chars=require('pinyin/data/dict-zi-web.js');
const phrases=require('pinyin/data/phrases-dict.js');
const entries=new Map();
function code(text){return text.toLowerCase().normalize('NFD').replaceAll('u\u0308','v').replace(/\p{M}/gu,'').replace(/[^a-z]/g,'');}
function add(py,word,front=false){if(!py||!/^[\u3400-\u9fff]+$/u.test(word))return;let list=entries.get(py);if(!list)entries.set(py,list=[]);if(!list.includes(word)){front?list.unshift(word):list.push(word);}}
for(const [py,words] of Object.entries(chars))for(const word of words)add(code(py),word);
for(const [word,syllables] of Object.entries(phrases))add(syllables.map(v=>code(v[0])).join(''),word);
const defaults={'ni':'你','hao':'好','ma':'吗','wo':'我','shi':'是','de':'的','zhong':'中','wen':'文','shu':'输','ru':'入','nihao':'你好','zhongwen':'中文','shuru':'输入','zhongduan':'终端','ceshi':'测试','xiexie':'谢谢','zaijian':'再见','keyi':'可以','qing':'请','bangwo':'帮我','yunxing':'运行','mingling':'命令','nihaoma':'你好吗','woaini':'我爱你','wenjian':'文件','mulu':'目录','zhongwenshuru':'中文输入'};
for(const [py,word] of Object.entries(defaults)){const list=entries.get(py)||[];entries.set(py,[word,...list.filter(v=>v!==word)]);}
const output=[...entries].sort(([a],[b])=>a.localeCompare(b)).map(([py,words])=>py+'\t'+words.slice(0,96).join(' ')).join('\n');
const root=path.join(__dirname,'../android-client/app/src/main/assets/keyboard');
fs.writeFileSync(path.join(root,'pinyin.bin'),zlib.gzipSync(Buffer.from(output)));
fs.writeFileSync(path.join(root,'SOURCE.txt'),'Offline reverse pinyin dictionary derived from npm pinyin 2.11.2, hotoo/pinyin, MIT.\nSource: https://registry.npmjs.org/pinyin/-/pinyin-2.11.2.tgz\nBuilt by scripts/build-pinyin.cjs. Fixed candidate ordering; no learning or input history.\n');
console.log(entries.size+' pinyin spellings; '+Buffer.byteLength(output)+' bytes before compression');
