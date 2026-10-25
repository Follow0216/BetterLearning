const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const file = path.join(__dirname, '../src/main/ets/features/multiagent/LearningV2State.ets');
const js = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText;
const statuses = {
  WAITING_INPUT: 'WAITING_INPUT', QUEUED: 'QUEUED', RUNNING: 'RUNNING',
  CANCELLING: 'CANCELLING', SUCCEEDED: 'SUCCEEDED', FAILED: 'FAILED',
  CANCELLED: 'CANCELLED', TIMED_OUT: 'TIMED_OUT'
};
const testModule = { exports: {} };
vm.runInNewContext(js, {
  exports: testModule.exports,
  require: () => ({ V2TutorRequestStatus: statuses, V2RunStatus: statuses })
}, { filename: file });
const { accept_v2_detail } = testModule.exports;
const detail = (requestId, revision, currentRunId, runRevision, status = 'RUNNING') => ({
  request: { requestId, questionId: '1', revision, currentRunId, status },
  run: currentRunId === null ? null : { runId: currentRunId, revision: runRevision, status,
    runNo: currentRunId === 'run-b' ? 2 : 1,
    retryOfRunId: currentRunId === 'run-b' ? 'run-a' : null },
  runs: currentRunId === null ? [] : [{ runId: currentRunId, revision: runRevision, status,
    runNo: currentRunId === 'run-b' ? 2 : 1, retryOfRunId: currentRunId === 'run-b' ? 'run-a' : null }],
  result: null
});
const old = detail('request-a', 4, 'run-a', 2);
assert.equal(accept_v2_detail(null, old), true);
assert.equal(accept_v2_detail(old, detail('request-b', 5, 'run-b', 1)), false);
assert.equal(accept_v2_detail(old, detail('request-a', 3, 'run-a', 4)), false);
assert.equal(accept_v2_detail(old, detail('request-a', 4, 'run-b', 1)), false);
assert.equal(accept_v2_detail(old, detail('request-a', 5, 'run-b', 1)), false);
assert.equal(accept_v2_detail(old, detail('request-a', 5, 'run-a', 1)), false);
assert.equal(accept_v2_detail(old, detail('request-a', 5, 'run-a', 3)), true);
const failed = detail('request-a', 4, 'run-a', 2, 'FAILED');
const validRetry = detail('request-a', 5, 'run-b', 1, 'QUEUED');
validRetry.runs = [failed.run, validRetry.run];
assert.equal(accept_v2_detail(failed, validRetry), true);
assert.equal(accept_v2_detail(failed, detail('request-a', 5, 'run-a', 3, 'RUNNING')), false);
const badRetry = detail('request-a', 5, 'run-b', 1, 'QUEUED');
badRetry.run.retryOfRunId = 'other';
badRetry.runs = [failed.run, badRetry.run];
assert.equal(accept_v2_detail(failed, badRetry), false);
const done = detail('request-a', 6, 'run-a', 3, 'SUCCEEDED');
done.result = { resultId: 'result-a' };
const rollback = detail('request-a', 7, 'run-a', 4, 'RUNNING');
assert.equal(accept_v2_detail(done, rollback), false);
const changedResult = detail('request-a', 7, 'run-a', 4, 'SUCCEEDED');
changedResult.result = { resultId: 'result-b' };
assert.equal(accept_v2_detail(done, changedResult), false);
const recovered = detail('request-a', 6, 'run-b', 1, 'QUEUED');
const missedTerminal = detail('request-a', 5, 'run-a', 3, 'FAILED');
recovered.runs = [missedTerminal.run, recovered.run];
assert.equal(accept_v2_detail(old, recovered), true, '断线漏掉失败事件后可恢复到新 run');
const multiple = detail('request-a', 9, 'run-c', 1, 'RUNNING');
multiple.run.runNo = 3;
multiple.run.retryOfRunId = 'run-b';
const middle = detail('request-a', 8, 'run-b', 3, 'TIMED_OUT');
multiple.runs = [missedTerminal.run, middle.run, multiple.run];
assert.equal(accept_v2_detail(old, multiple), true, '断线期间可跨多个显式 retry');
const badChain = detail('request-a', 9, 'run-c', 1, 'RUNNING');
badChain.run.runNo = 3;
badChain.run.retryOfRunId = 'other';
badChain.runs = [missedTerminal.run, middle.run, badChain.run];
assert.equal(accept_v2_detail(old, badChain), false, '错误 retryOf 链拒绝');
const unproven = detail('request-a', 6, 'run-b', 1, 'QUEUED');
unproven.runs = [old.run, unproven.run];
assert.equal(accept_v2_detail(old, unproven), false, '未证明旧 run 终态不得切换');
const terminalCancelled = detail('request-a', 4, 'run-a', 3, 'CANCELLED');
assert.equal(accept_v2_detail(terminalCancelled, recovered), false, '本机已见取消终态不可换 run');
const waiting = detail('request-a', 1, null, 0, 'WAITING_INPUT');
assert.equal(accept_v2_detail(waiting, multiple), true, '抬笔前离线也可恢复完整链');
const retry = detail('request-a', 5, 'run-b', 1);
assert.equal(accept_v2_detail(retry, detail('request-a', 4, 'run-a', 8)), false);
console.log('LearningV2State: 19 个状态迁移断言通过');
