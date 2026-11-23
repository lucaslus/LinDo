import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
const source=readFileSync(new URL('../composeApp/src/commonMain/kotlin/do_/linux/lindo/core/network/WebVerificationProbe.kt',import.meta.url),'utf8').split('= """')[1].split('""".trimIndent()')[0];
const flush=()=>new Promise(r=>setImmediate(r));
function page({preload,legacy=false,challenge=false,csrf=true,body='',title='',pathname='/',response,origin='https://linux.do'}={}) {
 let calls=0;
 let lastUrl='';
 const context=vm.createContext({location:{origin,pathname},document:{title,readyState:'complete',body:{innerText:body},
  querySelector:s=>s.includes('csrf-token')?(csrf?{}:null):(challenge?{}:null),
  getElementById:()=>preload?{textContent:legacy?'':JSON.stringify(preload),dataset:{preloaded:JSON.stringify(preload)}}:null},
  AbortController,setTimeout:()=>1,clearTimeout:()=>{},fetch:async(u)=>{calls++;lastUrl=u;return response || {ok:true,headers:{get:()=>null},json:async()=>({topic_list:{topics:[]}})};}});
 context.window=context;
 return {poll:()=>JSON.parse(vm.runInContext(source,context)),get calls(){return calls;},get lastUrl(){return lastUrl;},context};
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
test('topic path fallback probes exact topic json and resolves post_stream',async()=>{
 const p=page({pathname:'/t/topic/1895432',response:{ok:true,headers:{get:()=>null},json:async()=>({post_stream:{posts:[{id:1}]}})}});
 p.poll();await flush();assert.equal(p.lastUrl,'/t/1895432.json?track_visit=false');assert.equal(p.poll().ok,true);assert.equal(p.calls,1);
});
test('discourse origin 500 Oops page is not misclassified as challenge and recovers via json probe',async()=>{
 const p=page({csrf:false,body:'Oops\nThe software powering this discussion forum encountered an unexpected problem.'});
 const r1=p.poll();assert.equal(r1.challenge,undefined);await flush();assert.equal(p.poll().ok,true);assert.equal(p.calls,1);
});
test('/challenge origin 404 completes immediately with zero extra XHR probes',()=>{
 const p=page({pathname:'/challenge',body:'Oops! That page doesn’t exist or is private.'});
 const r=p.poll();assert.equal(r.ok,true);assert.equal(r.originReached,true);assert.equal(p.calls,0);
});
test('Cloudflare broken error page ("Something went wrong" / "error code: 1034") returns cfErrorPage immediately with 0 extra XHR probes',()=>{
 const p1=page({csrf:false,title:'Something went wrong'});
 assert.equal(p1.poll().cfErrorPage,true);assert.equal(p1.calls,0);
 const p2=page({csrf:false,body:'error code: 1034'});
 assert.equal(p2.poll().cfErrorPage,true);assert.equal(p2.calls,0);
});
