import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
const source=readFileSync(new URL('../composeApp/src/commonMain/kotlin/do_/linux/lindo/core/network/WebVerificationProbe.kt',import.meta.url),'utf8').split('= """')[1].split('""".trimIndent()')[0];
const flush=()=>new Promise(r=>setImmediate(r));
function page({preload,legacy=false,challenge=false,csrf=true,body='',height=0,title='',pathname='/',response,origin='https://linux.do'}={}) {
 let calls=0;
 let lastUrl='';
 const context=vm.createContext({location:{origin,pathname},document:{title,readyState:'complete',body:{innerText:body,children:body?[{}]:[],scrollHeight:height},
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

test('a textual waiting page stays noninteractive and issues no request',()=> {
 const p=page({title:'Just a moment',body:'Please wait',height:160});
 assert.deepEqual(p.poll(),{challenge:true,widget:false,stageDom:true,interactive:false});
 assert.equal(p.calls,0);
});

test('#d-splash with live csrf-token meta completes immediately even while readyState is loading', () => {
 let calls = 0;
 const ctx = vm.createContext({
  location: { origin: 'https://linux.do', pathname: '/' },
  document: {
   title: 'LINUX DO',
   readyState: 'loading',
   body: { innerText: '', children: [{}], scrollHeight: 400 },
   querySelector: (s) => {
    if (s.includes('csrf-token')) return { content: 'live_discourse_csrf_tok' };
    if (s.includes('#d-splash')) return {};
    return null;
   },
   getElementById: () => null
  },
  AbortController,
  setTimeout: () => 1,
  clearTimeout: () => {},
  fetch: async () => { calls++; return { ok: true, headers: { get: () => null }, json: async () => ({}) }; }
 });
 ctx.window = ctx;
 const r = JSON.parse(vm.runInContext(source, ctx));
 assert.equal(r.ok, true);
 assert.equal(r.csrf, 'live_discourse_csrf_tok');
 assert.equal(calls, 0);
});

const fullKt = readFileSync(new URL('../composeApp/src/commonMain/kotlin/do_/linux/lindo/core/network/WebVerificationProbe.kt', import.meta.url), 'utf8');
const forceRefreshSource = fullKt.split('internal fun forceRefreshCsrfProbeScript(): String = """')[1].split('""".trimIndent()')[0];
const rotatedTemplateSource = fullKt.split('private val ROTATED_CLEARANCE_XHR_PROBE_TEMPLATE: String = """')[1].split('""".trimIndent()')[0];

test('/challenge plain Not Found. text completes immediately while root plain Not Found. never false-passes', () => {
  const challenge404 = page({ pathname: '/challenge', csrf: false, body: 'Not Found.' });
  const r1 = challenge404.poll();
  assert.equal(r1.ok, true);
  assert.equal(r1.originReached, true);
  assert.equal(challenge404.calls, 0);

  const rootUnknown = page({ pathname: '/', csrf: false, body: 'Not Found.' });
  const r2 = rootUnknown.poll();
  assert.equal(r2.ok, undefined);
  assert.equal(rootUnknown.calls, 0);
});

test('forceRefreshCsrfProbeScript sends XMLHttpRequest + JSON headers and single-flights clean-IP 200 & challenged 403', async () => {
  let capturedCalls = [];
  const ctx200 = vm.createContext({
    location: { origin: 'https://linux.do', pathname: '/challenge' },
    document: { readyState: 'complete' },
    AbortController,
    setTimeout: () => 1,
    clearTimeout: () => {},
    fetch: async (url, opts) => {
      capturedCalls.push({ url, opts });
      return {
        status: 200,
        headers: { get: () => null },
        json: async () => ({ csrf: 'clean_ip_csrf_001' })
      };
    }
  });
  ctx200.window = ctx200;
  assert.equal(JSON.parse(vm.runInContext(forceRefreshSource, ctx200)).pending, true);
  assert.equal(JSON.parse(vm.runInContext(forceRefreshSource, ctx200)).pending, true);
  assert.equal(capturedCalls.length, 1);
  assert.equal(capturedCalls[0].url, '/session/csrf.json');
  assert.equal(capturedCalls[0].opts.credentials, 'include');
  assert.equal(capturedCalls[0].opts.cache, 'no-store');
  assert.equal(capturedCalls[0].opts.headers['Accept'], 'application/json');
  assert.equal(capturedCalls[0].opts.headers['X-Requested-With'], 'XMLHttpRequest');
  await flush();
  const res200 = JSON.parse(vm.runInContext(forceRefreshSource, ctx200));
  assert.deepEqual(res200, { status: 200, challenged: false, ok: true, csrf: 'clean_ip_csrf_001' });
  assert.equal(capturedCalls.length, 1);

  const ctx403 = vm.createContext({
    location: { origin: 'https://linux.do', pathname: '/challenge' },
    document: { readyState: 'complete' },
    AbortController,
    setTimeout: () => 1,
    clearTimeout: () => {},
    fetch: async () => ({
      status: 403,
      headers: { get: (k) => (k.toLowerCase() === 'cf-mitigated' ? 'challenge' : null) },
      json: async () => ({})
    })
  });
  ctx403.window = ctx403;
  vm.runInContext(forceRefreshSource, ctx403);
  await flush();
  assert.deepEqual(JSON.parse(vm.runInContext(forceRefreshSource, ctx403)), { status: 403, challenged: true, ok: false });
});

test('rotatedClearanceXhrProbeScript confirms rotated cf_clearance even on challenge DOM, deduplicates per candidate, and retries transient status 0', async () => {
  let now = 10_000;
  let calls = [];
  let nextResp = {
    status: 200,
    cfMitigated: '',
    csrf: 'rotated_xhr_csrf_777',
    throwErr: false
  };
  const ctx = vm.createContext({
    location: { origin: 'https://linux.do', pathname: '/challenge' },
    document: { readyState: 'interactive', title: 'Just a moment...' },
    Date: { now: () => now },
    AbortController,
    setTimeout: () => 1,
    clearTimeout: () => {},
    fetch: async (url, opts) => {
      calls.push({ url, opts });
      if (nextResp.throwErr) throw new Error('navigation aborted');
      return {
        status: nextResp.status,
        headers: { get: (k) => (k.toLowerCase() === 'cf-mitigated' ? nextResp.cfMitigated : null) },
        json: async () => ({ csrf: nextResp.csrf })
      };
    }
  });
  ctx.window = ctx;
  const runForKey = (key) => JSON.parse(
    vm.runInContext(rotatedTemplateSource.replace('__LINDO_CANDIDATE_KEY__', JSON.stringify(key)), ctx)
  );

  // 1. Candidate v1 confirms 200 OK with X-Requested-With: XMLHttpRequest even while title is "Just a moment..."
  assert.equal(runForKey('cand_v1').pending, true);
  assert.equal(runForKey('cand_v1').pending, true);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].opts.headers['X-Requested-With'], 'XMLHttpRequest');
  assert.equal(calls[0].opts.headers['Accept'], 'application/json');
  await flush();
  const ok1 = runForKey('cand_v1');
  assert.equal(ok1.pending, false);
  assert.equal(ok1.ok, true);
  assert.equal(ok1.status, 200);
  assert.equal(ok1.challenged, false);
  assert.equal(ok1.csrf, 'rotated_xhr_csrf_777');
  assert.equal(calls.length, 1);

  // 2. Transient status=0 abort during post-Turnstile redirect retries after >=600ms and succeeds
  nextResp = { status: 0, cfMitigated: '', csrf: '', throwErr: true };
  assert.equal(runForKey('cand_v2').pending, true);
  await flush();
  assert.equal(runForKey('cand_v2').status, 0);
  assert.equal(calls.length, 2);
  // Before 600ms: cached status=0
  now += 200;
  assert.equal(runForKey('cand_v2').status, 0);
  assert.equal(calls.length, 2);
  // After 600ms: retries and confirms 200 OK
  now += 500;
  nextResp = { status: 200, cfMitigated: '', csrf: 'recovered_csrf_888', throwErr: false };
  assert.equal(runForKey('cand_v2').pending, true);
  await flush();
  const ok2 = runForKey('cand_v2');
  assert.equal(ok2.ok, true);
  assert.equal(ok2.csrf, 'recovered_csrf_888');
  assert.equal(calls.length, 3);
});
