#!/usr/bin/env node
// Actual renderer marker parser/click handlers. DOM and native bridge are controlled doubles.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const assets=path.join(__dirname,'../src/main/resources/rawfile/question-markdown');
const id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',unknown='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
let click,opens=[],focuses=0,prevented=0;
const content={style:{},innerHTML:'',setAttribute(){},addEventListener(event,handler){assert.equal(event,'click');click=handler;}};
const sandbox={window:{markdownit:require(path.join(assets,'vendor/markdown-it.min.js')),texmath:require(path.join(assets,'vendor/texmath.js')),katex:require(path.join(assets,'vendor/katex.min.js')),scrollTo(){},BetterLearningCitations:{open:value=>opens.push(value)}},document:{getElementById:()=>content,documentElement:{style:{}}}};
vm.runInNewContext(fs.readFileSync(path.join(assets,'renderer.js'),'utf8'),sandbox);
let cases=0;function check(name,run){try{run();cases++;}catch(e){throw new Error(name+': '+e.message,{cause:e});}}
const labels=[{citationId:id,label:'参考 <img src=x onerror=alert(1)>'}];
function render(text,citations=labels){assert.equal(sandbox.window.BetterLearningQuestion.render(text,16,false,citations),true);return content.innerHTML;}
check('known inline marker uses current result label with HTML escaping',()=>{const html=render('先看 [[citation:'+id+']] 再继续');assert.equal((html.match(/class="citation-marker"/g)||[]).length,1);assert.ok(html.includes('参考 &lt;img'));assert.ok(!html.includes('<img'));assert.ok(!html.includes('[[citation:'+id));});
check('unknown and malformed markers remain inert text',()=>{const html=render('[[citation:'+unknown+']] [[citation:../secret]]');assert.ok(html.includes('[[citation:'+unknown+']]'));assert.ok(!html.includes('<button'));});
check('code and formulas preserve literal marker content without making buttons',()=>{assert.ok(!render('`[[citation:'+id+']]`').includes('<button'));assert.ok(!render('```\n[[citation:'+id+']]\n```').includes('<button'));const html=render('$$\n\\text{[[citation:'+id+']]}\n$$');assert.ok(html.includes('katex'));assert.ok(!html.includes('<button'));});
check('only whitelist click reaches native callback and remembers source focus',()=>{render('[[citation:'+id+']]');const button={getAttribute:()=>id,focus(options){assert.equal(options.preventScroll,true);focuses++;}};click({target:{closest:()=>button},preventDefault(){prevented++;}});assert.deepEqual(opens,[id]);assert.equal(prevented,1);assert.equal(sandbox.window.BetterLearningQuestion.restoreCitationFocus(id),true);assert.equal(focuses,1);});
check('unlisted DOM id cannot invoke native action',()=>{click({target:{closest:()=>({getAttribute:()=>unknown})},preventDefault(){throw new Error('unknown intercepted');}});assert.deepEqual(opens,[id]);});
check('new result clears prior whitelist and focus',()=>{render('[[citation:'+id+']]',[]);assert.ok(!content.innerHTML.includes('<button'));assert.equal(sandbox.window.BetterLearningQuestion.restoreCitationFocus(id),false);click({target:{closest:()=>({getAttribute:()=>id})},preventDefault(){throw new Error('stale id intercepted');}});assert.deepEqual(opens,[id]);});
check('network-like labels cannot create links or handlers',()=>{const html=render('[[citation:'+id+']]',[{citationId:id,label:'https://evil.invalid " onclick="steal()'}]);assert.ok(!html.includes('href='));assert.ok(!html.includes('onclick="'));assert.ok(html.includes('&quot;'));});
console.log(JSON.stringify({cases,result:'PASS',boundary:'actual local renderer and event handler with DOM/native bridge doubles; no ArkWeb or device'}));
