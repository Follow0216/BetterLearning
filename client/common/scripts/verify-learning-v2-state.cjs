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
  runs: [],
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
assert.equal(accept_v2_detail(failed, detail('request-a', 5, 'run-b', 1, 'QUEUED')), true);
assert.equal(accept_v2_detail(failed, detail('request-a', 5, 'run-a', 3, 'RUNNING')), false);
const badRetry = detail('request-a', 5, 'run-b', 1, 'QUEUED');
badRetry.run.retryOfRunId = 'other';
assert.equal(accept_v2_detail(failed, badRetry), false);
const done = detail('request-a', 6, 'run-a', 3, 'SUCCEEDED');
done.result = { resultId: 'result-a' };
const rollback = detail('request-a', 7, 'run-a', 4, 'RUNNING');
assert.equal(accept_v2_detail(done, rollback), false);
const changedResult = detail('request-a', 7, 'run-a', 4, 'SUCCEEDED');
changedResult.result = { resultId: 'result-b' };
assert.equal(accept_v2_detail(done, changedResult), false);
const retry = detail('request-a', 5, 'run-b', 1);
assert.equal(accept_v2_detail(retry, detail('request-a', 4, 'run-a', 8)), false);
console.log('LearningV2State: 13 个状态迁移断言通过');
