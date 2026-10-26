#!/usr/bin/env node
// Device-free integration checks only; never starts a build, service or device tool.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {execFile,execFileSync}=require('node:child_process');
const {promisify}=require('node:util');const run=promisify(execFile);
const root=path.resolve(__dirname,'../..');
const specs=[
 ['p5-client-suites','client/scripts/verify-multi-agent-clients.cjs'],
 ['navigation','client/scripts/checks/navigation-check.cjs',root],
 ['native-toast','client/scripts/checks/native-toast-check.cjs',root],
 ['account-common','client/scripts/checks/account-common-check.cjs',root],
 ['account-form','client/scripts/checks/account-form-check.cjs',root],
 ['toast-store','client/scripts/checks/toast-store-check.mjs'],
 ['auth-toast','client/scripts/checks/auth-toast-check.mjs']
];
function files(directory){return fs.readdirSync(directory,{withFileTypes:true}).flatMap(entry=>{if(['node_modules','oh_modules','.hvigor','build','.ohpm'].includes(entry.name))return [];const f=path.join(directory,entry.name);return entry.isDirectory()?files(f):/\.(ets|ts|js|css)$/.test(f)?[f]:[];});}
(async()=>{
 const runtimeCommit=execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim();
 const incoming='8944f092e3cada4443c8e18c47709e522ff309dc';
 execFileSync('git',['merge-base','--is-ancestor',incoming,'HEAD'],{cwd:root});
 const drift=execFileSync('git',['diff','--name-only','--','client'],{cwd:root,encoding:'utf8'}).trim();
 if(drift)throw new Error('Tracked client source drift: '+drift);
 const sourceHashes={};for(const file of files(path.join(root,'client')).sort())sourceHashes[path.relative(root,file)]=crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
 const results=await Promise.all(specs.map(async([name,...args])=>{try{const r=await run(process.execPath,args,{cwd:root,env:process.env,maxBuffer:2*1024*1024});return {name,command:[process.execPath,...args],exitCode:0,stdout:r.stdout.trim(),stderr:r.stderr.trim()};}catch(e){return {name,command:[process.execPath,...args],exitCode:e.code??1,stdout:e.stdout?.trim()??'',stderr:e.stderr?.trim()??e.message};}}));
 const result={checkedAt:new Date().toISOString(),runtimeCommit,incoming,trackedClientDrift:false,boundary:'actual source VM/SDK parser and preservation checks; no Hvigor, service, model or device',result:results.every(x=>x.exitCode===0)?'PASS':'FAIL',sourceHashes,results};
 if(process.argv[2])fs.writeFileSync(path.resolve(root,process.argv[2]),JSON.stringify(result,null,2)+'\n');
 for(const r of results)console.log((r.exitCode===0?'PASS ':'FAIL ')+r.name+(r.exitCode===0?'':'\n'+r.stderr));
 console.log('RESULT '+result.result+' ('+results.length+' integration groups)');if(result.result==='FAIL')process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1;});
