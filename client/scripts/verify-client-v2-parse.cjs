#!/usr/bin/env node
// SDK syntax parser only; no ArkUI lowering, Hvigor, signing, native build or devices.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {execFileSync}=require('node:child_process');
const root=path.resolve(__dirname,'../..');
const loader=process.env.BETTERLEARNING_ETS_LOADER||'/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts=require(path.join(loader,'node_modules/typescript'));
const options=ts.readConfigFile(path.join(loader,'tsconfig.json'),ts.sys.readFile).config.compilerOptions;
const changed=execFileSync('git',['diff','--name-only','50ea1a9820c7489052f3c412b7ea1a69e13bdd35','--','client'],{cwd:root,encoding:'utf8'});
const untracked=execFileSync('git',['ls-files','--others','--exclude-standard','--','client'],{cwd:root,encoding:'utf8'});
const files=[...new Set((changed+'\n'+untracked).trim().split('\n').filter(f=>f.endsWith('.ets')))];
let diagnostics=0;
for(const relative of files){const file=path.join(root,relative);if(!fs.existsSync(file))continue;const source=ts.createSourceFile(file,fs.readFileSync(file,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.ETS,options);for(const d of source.parseDiagnostics){diagnostics++;console.error(relative+': '+ts.flattenDiagnosticMessageText(d.messageText,' '));}}
assert.equal(diagnostics,0);
console.log(JSON.stringify({files:files.length,diagnostics,result:'PASS',boundary:'SDK ETS syntax parser only; no full project type check or ArkUI lowering'}));
