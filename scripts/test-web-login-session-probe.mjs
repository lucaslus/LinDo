import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
const kotlin=readFileSync(new URL('../composeApp/src/commonMain/kotlin/do_/linux/lindo/core/network/WebLoginSessionProbe.kt',import.meta.url),'utf8');
const body=kotlin.split('= """')[1].split('""".trimIndent()')[0];
const user={id:42,username:'reader',name:'Reader'};
const flush=()=>new Promise(resolve=>setImmediate(resolve));
function page({path='/',challenge=false,preload=null,response,legacyThrows=false}={}) {
 let now=0,calls=0;
 const context=vm.createContext({location:{origin:'https://linux.do',pathname:path},
  document:{readyState:'complete',title:'LINUX DO',querySelector:s=>s.includes('csrf-token')?{content:'csrf'}:challenge?{}:null,
   getElementById:()=>preload?{dataset:{preloaded:JSON.stringify({currentUser:preload})}}:null},
  Date:{now:()=>now,parse:Date.parse},AbortController,setTimeout:()=>1,clearTimeout:()=>{},
  fetch:async()=>{calls++;return response?response(calls):{ok:true,status:200,headers:{get:()=>null},json:async()=>({current_user:user})};}});
 context.window=context;
 if(legacyThrows) context.Discourse={__container__:{lookup(){throw Error('legacy removed');}}};
 return {poll(owner='unchanged-cookie'){return JSON.parse(vm.runInContext(body.replace('${JsonPrimitive(sessionOwner)}',JSON.stringify(owner)),context));},
  tick(ms){now+=ms;},get calls(){return calls;},context};
}
test('string and object preloads both complete immediately without requests',()=>{
 for(const preload of [user,JSON.stringify(user)]) {const p=page({preload});assert.equal(p.poll().user.username,'reader');assert.equal(p.calls,0);}
});
test('unchanged session cookie and missing/throwing legacy API still complete',async()=>{
 const p=page({legacyThrows:true});assert.equal(p.poll().state,'waiting');await flush();assert.equal(p.poll().state,'ready');assert.equal(p.calls,1);
});
test('polling is single flight and a resolved response never refetches',async()=>{
 let finish;const pending=new Promise(r=>finish=r);const p=page({response:()=>pending});
 for(let i=0;i<10;i++)p.poll();assert.equal(p.calls,1);
 finish({ok:true,status:200,headers:{get:()=>null},json:async()=>({current_user:user})});await flush();
 for(let i=0;i<10;i++)assert.equal(p.poll().state,'ready');assert.equal(p.calls,1);
});
test('login, OTP and Cloudflare pages do not get background probes',()=>{
 for(const path of ['/login','/session/otp/token','/auth/google_oauth2','/user-api-key/new']){const p=page({path});assert.equal(p.poll().state,'login');assert.equal(p.calls,0);}
 const p=page({challenge:true});assert.equal(p.poll().state,'challenge');assert.equal(p.calls,0);
});
test('guest responses never become authenticated just because a cookie exists',async()=>{
 const p=page({response:()=>({ok:false,status:401,headers:{get:()=>null}})});p.poll();await flush();
 assert.equal(p.poll('existing-cookie').state,'waiting');await flush();
 assert.equal(p.poll('existing-cookie').state,'guest');const count=p.calls;for(let i=0;i<10;i++)p.poll('existing-cookie');assert.equal(p.calls,count);
});
test('429 waits two seconds by default and retries only once',async()=>{
 const p=page({response:()=>({ok:false,status:429,headers:{get:()=>null}})});p.poll();await flush();
 p.tick(1999);p.poll();assert.equal(p.calls,1);p.tick(1);p.poll();await flush();assert.equal(p.calls,2);
 assert.equal(p.poll().state,'error');p.tick(100000);p.poll();assert.equal(p.calls,2);
});
test('long Retry-After never triggers a short immediate retry',async()=>{
 const p=page({response:()=>({ok:false,status:429,headers:{get:k=>k==='Retry-After'?'120':null}})});p.poll();await flush();
 assert.equal(p.poll().state,'error');p.tick(2000);p.poll();assert.equal(p.calls,1);
});
test('session change discards an older pending user response',async()=>{
 let finish;const p=page({response:n=>n===1?new Promise(r=>finish=r):{ok:true,status:200,headers:{get:()=>null},json:async()=>({current_user:{id:43,username:'second'}})}});
 p.poll('first');p.poll('second');await flush();
 finish({ok:true,status:200,headers:{get:()=>null},json:async()=>({current_user:user})});await flush();
 assert.equal(p.poll('second').user.username,'second');
});
