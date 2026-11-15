import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
const source=readFileSync(new URL('../composeApp/src/commonMain/kotlin/do_/linux/lindo/core/network/WebVerificationProbe.kt',import.meta.url),'utf8').split('= """')[1].split('""".trimIndent()')[0];
const flush=()=>new Promise(r=>setImmediate(r));
function page({preload,legacy=false,challenge=false,csrf=true,body='',response,origin='https://linux.do'}={}) {
 let calls=0;
 const context=vm.createContext({location:{origin},document:{readyState:'complete',body:{innerText:body},
  querySelector:s=>s.includes('csrf-token')?(csrf?{}:null):(challenge?{}:null),
  getElementById:()=>preload?{textContent:legacy?'':JSON.stringify(preload),dataset:{preloaded:JSON.stringify(preload)}}:null},
  AbortController,setTimeout:()=>1,clearTimeout:()=>{},fetch:async()=>{calls++;return response || {ok:true,headers:{get:()=>null},json:async()=>({topic_list:{topics:[]}})};}});
 context.window=context;
 return {poll:()=>JSON.parse(vm.runInContext(source,context)),get calls(){return calls;},context};
}
test('real forum document finishes without sending another request (legacy and modern)',()=>{
 for(const legacy of [true,false]){const p=page({legacy,preload:{site:'{}'}});assert.equal(p.poll().ok,true);assert.equal(p.calls,0);}
});
test('live challenge is never probed or auto-clicked even with stale preload',()=>{
 const p=page({challenge:true,preload:{site:'{}'}});for(let i=0;i<50;i++)assert.equal(p.poll().challenge,true);assert.equal(p.calls,0);
});
test('polls share one fallback probe and retain its confirmed result',async()=>{
 const p=page();for(let i=0;i<20;i++)p.poll();assert.equal(p.calls,1);await flush();for(let i=0;i<20;i++)assert.equal(p.poll().ok,true);assert.equal(p.calls,1);
});
test('challenged fallback stops and never navigates to latest.json',async()=>{
 const p=page({response:{headers:{get:()=> 'challenge'}}});p.poll();await flush();for(let i=0;i<20;i++)assert.equal(p.poll().blocked,true);assert.equal(p.calls,1);assert.equal(p.context.location.href,undefined);
});
test('rate limit is delivered once per unchanged result without a request loop',async()=>{
 const p=page({response:{status:429,headers:{get:k=>k==='Retry-After'?'30':null}}});p.poll();await flush();assert.equal(p.poll().status,429);for(let i=0;i<20;i++)p.poll();assert.equal(p.calls,1);
});
test('unknown and other-origin pages never count as completion',()=>{
 for(const options of [{csrf:false},{origin:'https://other.example',preload:{site:'{}'}}]){const p=page(options);assert.equal(p.poll().ok,undefined);assert.equal(p.calls,0);}
});
test('a rendered JSON response finishes without further requests',()=>{
 const p=page({body:JSON.stringify({topic_list:{topics:[]}})});assert.equal(p.poll().ok,true);assert.equal(p.calls,0);
});
