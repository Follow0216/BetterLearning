#!/usr/bin/env node
// Device-free checks only. Native network/graphics doubles are labeled in each suite.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {execFile}=require('node:child_process');
const {promisify}=require('node:util');const run=promisify(execFile);
const root=path.resolve(__dirname,'../..');
const scripts=[
 'client/scripts/verify-client-v2-parse.cjs',
 'client/common/scripts/verify-learning-v2-state.cjs',
 'client/common/scripts/verify-learning-v2-transport.cjs',
 'client/common/scripts/verify-v2-account-transport.cjs',
 'client/common/scripts/verify-citation-geometry.cjs',
 'client/common/scripts/verify-citation-markers.cjs',
 'client/common/scripts/verify-citation-inline.cjs',
 'client/common/scripts/verify-markdown-citation-bridge.cjs',
 'client/common/scripts/verify-citation-lifecycle.cjs',
 'client/common/scripts/verify-markdown-math.cjs',
 'client/apps/phone/scripts/verify-phone-v2-logic.cjs',
 'client/apps/phone/scripts/verify-phone-v2-async.cjs',
 'client/apps/phone/scripts/verify-phone-v2-page.cjs',
 'client/apps/phone/scripts/verify-phone-v2-refresh-error.cjs',
 'client/apps/phone/scripts/verify-phone-v2-home.cjs',
 'client/apps/tablet/scripts/verify-tablet-v2.cjs',
 'client/apps/tablet/scripts/verify-tablet-v2-canvas.cjs',
 'client/apps/tablet/scripts/verify-tablet-v2-history.cjs',
 'client/apps/tablet/scripts/verify-tablet-v2-citations.cjs',
 'client/apps/tablet/scripts/verify-tablet-back.cjs',
 'client/apps/tablet/scripts/verify-tablet-tutor-ui.cjs',
 'client/apps/tablet/scripts/verify-canvas-input.cjs',
 'client/apps/tablet/scripts/verify-canvas-pens.cjs',
 'client/apps/tablet/scripts/verify-canvas-viewport.cjs'
];
function files(directory){return fs.readdirSync(directory,{withFileTypes:true}).flatMap(entry=>{const f=path.join(directory,entry.name);if(['node_modules','oh_modules','.hvigor','build'].includes(entry.name))return [];return entry.isDirectory()?files(f):/\.(ets|cjs|js|css)$/.test(f)?[f]:[];});}
(async()=>{
 const sourceHashes={};for(const file of files(path.join(root,'client')).sort())sourceHashes[path.relative(root,file)]=crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
 const results=await Promise.all(scripts.map(async script=>{try{const r=await run(process.execPath,[script],{cwd:root,env:process.env,maxBuffer:1024*1024});return {script,exitCode:0,stdout:r.stdout.trim(),stderr:r.stderr.trim()};}catch(e){return {script,exitCode:e.code??1,stdout:e.stdout?.trim()??'',stderr:e.stderr?.trim()??e.message};}}));
 const result={checkedAt:new Date().toISOString(),boundary:'pure production logic and SDK-parser harnesses; no Hvigor, signing, device, real server or real model',result:results.every(r=>r.exitCode===0)?'PASS':'FAIL',sourceHashes,results};
 const json=JSON.stringify(result,null,2)+'\n';
 if(process.argv[2])fs.writeFileSync(path.resolve(root,process.argv[2]),json);
 for(const r of results)console.log((r.exitCode===0?'PASS ':'FAIL ')+r.script+'\n'+(r.exitCode===0?r.stdout:r.stderr));
 console.log('RESULT '+result.result+' ('+results.length+' suites)');if(result.result==='FAIL')process.exitCode=1;
})().catch(error=>{console.error(error);process.exitCode=1;});
