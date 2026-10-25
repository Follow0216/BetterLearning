#!/usr/bin/env node
// Actual production ETS geometry and local Markdown assets; no UI/device/provider claims.
const assert = require('node:assert/strict');
const fs = require('node:fs'); const path = require('node:path'); const vm = require('node:vm');
const loader = process.env.BETTERLEARNING_ETS_LOADER || '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader,'node_modules/typescript'));
const options = ts.readConfigFile(path.join(loader,'tsconfig.json'),ts.sys.readFile).config.compilerOptions;
const sourcePath = path.join(__dirname,'../src/main/ets/features/multiagent/CitationGeometry.ets');
const source = ts.createSourceFile(sourcePath,fs.readFileSync(sourcePath,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.ETS,options);
assert.equal(source.parseDiagnostics.length,0);
const code = source.statements.filter(s => !ts.isImportDeclaration(s)).map(s => s.getText(source).replace(/^export\s+/, '')).join('\n');
const emitted = ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2020},reportDiagnostics:true});
assert.equal(emitted.diagnostics.length,0);
const sandbox=vm.createContext({}); vm.runInContext(emitted.outputText,sandbox);
const api=vm.runInContext('({citation_text_range,citation_intersection,citation_pixel_region,citation_world_extent,citation_fit})',sandbox);
let cases=0; function check(name,run){try{run();cases++;}catch(e){throw new Error(name+': '+e.message,{cause:e});}}
const plain=x=>JSON.parse(JSON.stringify(x));
const segment=(index,x,pixelWidth=1024)=>({blockIndex:index,assetUrl:'/authenticated/'+index,blockWorldBounds:{x,y:-1024,width:1024,height:1024},pixelWidth,pixelHeight:pixelWidth,regionWorldBounds:{x:-50,y:-120,width:150,height:90}});
check('UTF16 exact emoji and formula source range',()=>{
 const s='前😀后 $x^2$'; const r=api.citation_text_range(s,1,3); assert.equal(r.selected,'😀'); assert.equal(r.before+r.selected+r.after,s);
 const formula=api.citation_text_range(s,5,s.length); assert.equal(formula.selected,'$x^2$');
});
for(const [start,end] of [[2,3],[1,2],[-1,2],[0,0],[0,99],[0.5,2],[NaN,2]]) check('reject invalid/split range '+start+':'+end,()=>assert.throws(()=>api.citation_text_range('前😀后',start,end)));
check('cross-block negative world coordinates and independent raster densities',()=>{
 const a=segment(0,-1024,1024),b=segment(1,0,2048);
 assert.deepEqual(plain(api.citation_pixel_region(a)),{x:974,y:904,width:50,height:90});
 assert.deepEqual(plain(api.citation_pixel_region(b)),{x:0,y:1808,width:200,height:180});
 assert.deepEqual(plain(api.citation_world_extent([a,b])),{x:-50,y:-120,width:150,height:90});
 assert.deepEqual(plain(api.citation_world_extent([a,b],true)),{x:-1024,y:-1024,width:2048,height:1024});
 const view=api.citation_fit(api.citation_world_extent([a,b]),324,204);
 assert.equal(view.scale,2); assert.equal(-50*view.scale+view.offsetX,12); assert.equal(-120*view.scale+view.offsetY,12);
});
check('cross-block output invariant under arbitrary pixel density',()=>{
 for(let density=1;density<=2;density+=0.125){const s=segment(0,-1024,1024*density);const p=api.citation_pixel_region(s);assert.equal(p.x/s.pixelWidth*1024,974);assert.equal(p.width/s.pixelWidth*1024,50);}
});
check('reject missing steps, duplicate tiles, nonintersecting bounds and invalid sizes',()=>{
 assert.throws(()=>api.citation_world_extent([])); assert.throws(()=>api.citation_world_extent([segment(0,-1024),segment(0,-1024)]));
 const s=segment(0,4000);assert.throws(()=>api.citation_pixel_region(s));
 for(const v of [0,-1,NaN,Infinity,1.5,99999])assert.throws(()=>api.citation_pixel_region({...segment(0,-1024),pixelWidth:v}));
 for(const v of [0,-1,NaN,Infinity])assert.throws(()=>api.citation_fit({x:0,y:0,width:1,height:1},v,200));
});
const assets=path.join(__dirname,'../src/main/resources/rawfile/question-markdown');
const content={innerHTML:'',style:{},setAttribute(){}};
const render={window:{markdownit:require(path.join(assets,'vendor/markdown-it.min.js')),texmath:require(path.join(assets,'vendor/texmath.js')),katex:require(path.join(assets,'vendor/katex.min.js')),scrollTo(){}},document:{getElementById:()=>content,documentElement:{style:{}}}};
vm.runInNewContext(fs.readFileSync(path.join(assets,'renderer.js'),'utf8'),render);
check('highlight preserves whole formula and resets on normal reading',()=>{
 assert.equal(render.window.BetterLearningQuestion.render('步骤\n\\[x^2+1=0\\]',16,true),true);
 assert.ok(content.innerHTML.includes('katex'));assert.equal(content.style.backgroundColor,'#FEF9C3');
 assert.equal(render.window.BetterLearningQuestion.render('普通笔记',16,false),true);assert.equal(content.style.backgroundColor,'');
});
check('64KiB notes remain rendered locally and external markup is inert',()=>{
 assert.equal(render.window.BetterLearningQuestion.render('字'.repeat(21000),16),true);
 assert.equal(render.window.BetterLearningQuestion.render('x'.repeat(65537),16),false);
 render.window.BetterLearningQuestion.render('<script>alert(1)</script> ![图](https://evil.invalid) [链接](https://evil.invalid)',16,true);
 assert.ok(!content.innerHTML.includes('<script>'));assert.ok(!content.innerHTML.includes('<img'));assert.ok(!content.innerHTML.includes('href='));
});
console.log(JSON.stringify({cases,result:'PASS',boundary:'production pure ETS execution and local renderer DOM stub; no ArkUI layout/device/network/model'}));
