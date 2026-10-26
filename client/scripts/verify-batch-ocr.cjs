#!/usr/bin/env node
// Executes the real DTO parser and draft-session logic. No model/device/network calls.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..');
const loader=process.env.BETTERLEARNING_ETS_LOADER||'/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts=require(path.join(loader,'node_modules/typescript'));
const parserOptions=ts.readConfigFile(path.join(loader,'tsconfig.json'),ts.sys.readFile).config.compilerOptions;
function load(relative,dependencies={}){
  const source=fs.readFileSync(path.join(root,relative),'utf8');
  const module={exports:{}};
  vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2021}}).outputText,
    {module,exports:module.exports,Observed:value=>value,require:key=>{if(!(key in dependencies))throw Error('Unexpected dependency '+key);return dependencies[key];},Map,Set,Number,Array,JSON,Date,Error});
  return module.exports;
}
const models=load('client/common/src/main/ets/features/tutor/BatchOcrModels.ets');
const id='12345678-1234-1234-1234-123456789abc';
const result={questions:[{itemId:'q1',bodyMarkdown:'求 $x+1=2$。',sourceIds:['image-0'],tileIds:['t1'],needsReview:true,warnings:[]}],
  sources:[{sourceId:'image-0',order:0,width:100,height:200}],tiles:[{tileId:'t1',sourceId:'image-0',x:0,y:0,width:100,height:200,repair:false,markdown:'求 $x+1=2$。'}],warnings:[],incomplete:false};
const job={requestId:id,status:'COMPLETED',stage:'DONE',completedTiles:1,totalTiles:1,result};
assert.ok(models.parse_batch_ocr_job(JSON.stringify(job),id));
for(const status of ['UNKNOWN','FAILED','CANCELLED'])assert.equal(models.parse_batch_ocr_job(JSON.stringify({...job,status}),id).status,status);
assert.equal(models.parse_batch_ocr_job(JSON.stringify(job),'00000000-0000-0000-0000-000000000000'),null);
const invalid=structuredClone(job);invalid.result.tiles[0].y=1;
assert.equal(models.parse_batch_ocr_job(JSON.stringify(invalid),id),null,'out-of-bounds provenance must fail');
const unknownRef=structuredClone(job);unknownRef.result.questions[0].tileIds=['missing'];
assert.equal(models.parse_batch_ocr_job(JSON.stringify(unknownRef),id),null);
const cleaned=[];let sequence=0;
const common={...models,MAX_QUESTION_MARKDOWN_LENGTH:20000,LearningSubject:{HIGHER_MATHEMATICS:'HIGHER_MATHEMATICS'},ManualQuestionInput:class{}};
const drafts=load('client/apps/phone/src/main/ets/features/ocr/BatchOcrDrafts.ets',{
  '@kit.ArkTS':{util:{generateRandomUUID:()=>`id-${++sequence}`}},common,
  './BatchQuestionImageSource':{discardBatchImage:p=>cleaned.push(p)}});
const store=new drafts.BatchDraftSession();store.bind('A');
const batch=new drafts.BatchReviewDraft([{path:'/owned/image.png',width:100,height:200,byteLength:10,contentType:'image/png'}]);store.batches.push(batch);
store.accept(batch,{...job,status:'RUNNING'});assert.equal(batch.drafts.length,0,'partial IDs must not enter editable drafts');
store.accept(batch,job);assert.equal(batch.drafts.length,1);assert.equal(batch.drafts[0].selected,false);
batch.drafts[0].bodyMarkdown='人工改过';batch.drafts[0].selected=true;
store.accept(batch,{...job,result:{...result,questions:[{...result.questions[0],bodyMarkdown:'轮询文本'},{...result.questions[0],itemId:'q2'}]}});
assert.equal(batch.drafts[0].bodyMarkdown,'人工改过');assert.equal(batch.drafts.length,1,'terminal result imports once');
batch.drafts[0].savedQuestionId='1';batch.job.status='INCOMPLETE';store.releaseConfirmedSources(batch);assert.equal(cleaned.length,0,'incomplete provenance remains');
batch.job.status='COMPLETED';store.releaseConfirmedSources(batch);assert.deepEqual(cleaned,['/owned/image.png']);
const failed=new drafts.BatchReviewDraft([{path:'/owned/partial.png'}]);store.batches.push(failed);failed.posted=true;
store.accept(failed,{...job,status:'FAILED',result:{...result,questions:[]}});
const manual=store.addManual(failed,0);assert.ok(manual);assert.equal(manual.bodyMarkdown,'');assert.equal(manual.selected,false);
assert.deepEqual(Array.from(manual.sourceIds),['image-0']);assert.deepEqual(Array.from(manual.tileIds),['t1']);
const unknownBatch=new drafts.BatchReviewDraft([{path:'/owned/unknown.png'}]);store.batches.push(unknownBatch);unknownBatch.posted=true;
store.accept(unknownBatch,{requestId:id,status:'UNKNOWN',stage:'UNKNOWN',completedTiles:0,totalTiles:1});
assert.equal(unknownBatch.resultImported,false,'local HTTP unknown without result must remain recoverable');
assert.equal(unknownBatch.reviewResult,null);assert.equal(unknownBatch.drafts.length,0);
store.accept(unknownBatch,{...job,status:'UNKNOWN',result:{...result,incomplete:true}});
assert.equal(unknownBatch.resultImported,true,'server-fenced UNKNOWN with partial result is imported once');
assert.ok(unknownBatch.reviewResult);assert.equal(unknownBatch.drafts.length,1);assert.equal(unknownBatch.drafts[0].selected,false);
unknownBatch.drafts[0].bodyMarkdown='中断后人工恢复';
store.accept(unknownBatch,job);assert.equal(unknownBatch.drafts[0].bodyMarkdown,'中断后人工恢复');
assert.equal(store.addManual(batch,0),null,'released original cannot gain a new manual draft from a late callback');
const selection=new drafts.BatchReviewDraft([{path:'/owned/selection.png'}]);store.batches.push(selection);
const valid=new drafts.BatchQuestionDraft({...result.questions[0],itemId:'valid',bodyMarkdown:'12. 求函数。\n（1）求导；（2）求极值。'});
const saved=new drafts.BatchQuestionDraft({...result.questions[0],itemId:'saved'});saved.savedQuestionId='2';
const pending=new drafts.BatchQuestionDraft({...result.questions[0],itemId:'pending'});pending.pendingInput={requestId:'same',bodyMarkdown:pending.bodyMarkdown};
const blank=new drafts.BatchQuestionDraft({...result.questions[0],itemId:'blank',bodyMarkdown:'   '});
selection.drafts=[valid,saved,pending,blank];valid.expanded=true;
store.selectAll(selection,true);assert.deepEqual(selection.drafts.map(d=>d.selected),[true,false,false,false]);
assert.equal(valid.expanded,true,'selection preserves the mounted editor expansion');
assert.equal(drafts.batch_question_label(valid.bodyMarkdown,0),'12.','original large-question number is retained with both subquestions');
assert.equal(selection.drafts.length,4,'frontend does not split subquestions');
assert.equal(drafts.batch_question_label('第十二题 求值',8),'第十二题');
assert.equal(drafts.batch_question_label('3.14 是圆周率',0),'题序 1');
assert.equal(drafts.batch_question_label('（1）求导',4),'题序 5','subquestion is not presented as an original large-question number');
assert.equal(drafts.batch_question_summary('x'.repeat(1000)).length,161,'folded summary is bounded');
valid.changeBody('12. 人工修改');assert.equal(valid.selected,false,'editing withdraws prior review acknowledgement');assert.equal(valid.expanded,true);
store.selectAll(selection,true);store.selectAll(selection,false);assert.ok(selection.drafts.every(d=>!d.selected));
assert.equal(pending.changeBody('不同正文'),false);assert.notEqual(pending.bodyMarkdown,'不同正文');
for(const stage of ['PREPARING','UPLOADING','QUEUED','VALIDATING','OCR','ASSEMBLE','RECONSTRUCTING','REPAIR'])assert.match(drafts.batch_ocr_stage_label(stage),/正在/);
assert.match(drafts.batch_ocr_stage_label('REPAIR'),/接缝/);
assert.match(drafts.batch_ocr_stage_label('ASSEMBLE'),/整理题目/,'production engine ASSEMBLE stage must be meaningful');
assert.equal(drafts.batch_start_can_retry_manually('POST',409,'A0455'),true,'known pre-insert active-batch conflict allows a manual start');
assert.equal(drafts.batch_start_can_retry_manually('POST',429,'A0502'),true,'known pre-insert queue-full response allows a manual start');
for(const [method,status,code] of [['POST',409,'A0445'],['POST',429,''],['POST',429,'A0501'],['POST',0,''],['POST',503,'C0001'],['GET',404,'A0606'],['GET',409,'A0455'],['GET',429,'A0502']]) {
  assert.equal(drafts.batch_start_can_retry_manually(method,status,code),false,`${method} ${status}/${code} must preserve request identity`);
}
const unresolved=new drafts.BatchReviewDraft([{path:'/owned/unresolved.jpg'}]);store.batches.push(unresolved);
store.bind('B');assert.equal(store.batches.length,0);assert.ok(cleaned.includes('/owned/unresolved.jpg'));assert.equal(store.owns('A'),false);
const files=['client/common/src/main/ets/features/tutor/BatchOcrModels.ets','client/common/src/main/ets/features/tutor/BatchOcrApi.ets',
'client/apps/phone/src/main/ets/features/ocr/BatchQuestionImageSource.ets','client/apps/phone/src/main/ets/features/ocr/BatchOcrDrafts.ets',
'client/apps/phone/src/main/ets/components/BatchOcrReview.ets','client/apps/phone/src/main/ets/components/BatchOcrSourceViewer.ets',
'client/apps/phone/src/main/ets/components/ManualEntry.ets'];
for(const relative of files){const sf=ts.createSourceFile(relative,fs.readFileSync(path.join(root,relative),'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.ETS,parserOptions);
assert.equal(sf.parseDiagnostics.length,0,relative+': '+sf.parseDiagnostics.map(d=>ts.flattenDiagnosticMessageText(d.messageText,' ')).join('; '));}
console.log(JSON.stringify({result:'PASS',checks:'real DTO/provenance parser; terminal import/edit preservation; unchecked defaults; conservative image retention; failed/UNKNOWN partial manual recovery; released-source late callback guard; token isolation; eligible select-all; edit acknowledgement reset; original numbering; bounded summaries; Chinese pipeline stages; manual retry eligibility; bounded ETS syntax',boundary:'No ArkUI lowering, project type checking, device or model validation'}));
