#!/usr/bin/env node
// Interleave controlled HTTP-like completions through the exact reducers used by PhoneTutorV2Page.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

const loader = process.env.BETTERLEARNING_ETS_LOADER || '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'betterlearning-phone-v2-async-'));
async function main() {
  const common = path.resolve(__dirname, '../../../common/src/main/ets/features/multiagent');
  const phone = path.resolve(__dirname, '../src/main/ets/model/PhoneTutorV2Logic.ets');
  fs.copyFileSync(path.join(common, 'LearningV2Models.ets'), path.join(temporary, 'LearningV2Models.ts'));
  fs.copyFileSync(path.join(common, 'LearningV2State.ets'), path.join(temporary, 'LearningV2State.ts'));
  const original = fs.readFileSync(phone, 'utf8');
  const source = original.replace(
    "import { accept_v2_detail, V2TutorRequest, V2TutorRequestDetail, V2TutorRequestStatus } from 'common';",
    "import { V2TutorRequest, V2TutorRequestDetail, V2TutorRequestStatus } from './LearningV2Models';\n" +
    "import { accept_v2_detail } from './LearningV2State';");
  assert.notEqual(source, original);
  fs.writeFileSync(path.join(temporary, 'PhoneTutorV2Logic.ts'), source);
  execFileSync(process.execPath, [path.join(loader,'node_modules/typescript/bin/tsc'), '--module', 'commonjs', '--target', 'es2020', '--outDir', temporary,
    path.join(temporary, 'LearningV2Models.ts'), path.join(temporary, 'LearningV2State.ts'),
    path.join(temporary, 'PhoneTutorV2Logic.ts')], { stdio: 'inherit' });
  const logic = require(path.join(temporary, 'PhoneTutorV2Logic.js'));
  const { V2TutorRequestStatus: status } = require(path.join(temporary, 'LearningV2Models.js'));
  const request = (id, revision, runId, state) => ({
    requestId: id, questionId: '12', prompt: '问题', mode: 'HINT', snapshotInput: { kind: 'NEW_CAPTURE' },
    snapshotOriginRequestId: null, snapshotFileRef: runId === null ? null : 'snapshot',
    currentRunId: runId, status: state, revision,
    createdAt: '2026-10-25T00:00:00Z', updatedAt: '2026-10-25T00:00:00Z'
  });
  const detail = (req, runNo = 1, runRevision = 1, retryOfRunId = null, priorRuns = []) => {
    const run = req.currentRunId === null ? null : {
      runId: req.currentRunId, requestId: req.requestId, runNo, retryOfRunId,
      status: req.status, revision: runRevision, steps: []
    };
    return { request: req, run, runs: run === null ? [] : [...priorRuns, run], result: null };
  };
  let state = { requests: [], selectedRequestId: '', detail: null };
  const post = deferred();
  const confirm = deferred();
  const poll = deferred();
  const retry = deferred();
  const latePoll = deferred();
  const onPost = post.promise.then((req) => { state = logic.phone_v2_apply_created(state, req); });
  const onConfirm = confirm.promise.then((received) => {
    state = logic.phone_v2_apply_created(state, received.request);
    state = logic.phone_v2_apply_detail(state, received);
  });
  const onPoll = poll.promise.then((received) => { state = logic.phone_v2_apply_detail(state, received); });
  const onRetry = retry.promise.then((received) => { state = logic.phone_v2_apply_detail(state, received); });
  const onLatePoll = latePoll.promise.then((received) => { state = logic.phone_v2_apply_detail(state, received); });

  post.resolve(request('r1', 0, null, status.WAITING_INPUT));
  await onPost;
  poll.resolve(detail(request('r1', 1, 'run1', status.RUNNING)));
  await onPoll;
  confirm.resolve(detail(request('r1', 0, null, status.WAITING_INPUT)));
  await onConfirm;
  assert.equal(state.detail.request.revision, 1, 'late confirmation must retain poll detail');
  assert.equal(state.requests[0].revision, 1, 'late confirmation must retain list revision');

  const failed = detail(request('r1', 2, 'run1', status.FAILED), 1, 2);
  state = logic.phone_v2_apply_detail(state, failed);
  retry.resolve(detail(request('r1', 3, 'run2', status.QUEUED), 2, 1, 'run1', [failed.run]));
  await onRetry;
  latePoll.resolve(failed);
  await onLatePoll;
  assert.equal(state.detail.request.currentRunId, 'run2', 'late poll must not replace retry run');
  assert.equal(state.requests[0].revision, 3, 'late poll must not replace retry summary');

  const missedFailure = detail(request('recovery', 1, 'old-run', status.RUNNING));
  const recovered = detail(request('recovery', 3, 'new-run', status.QUEUED), 2, 1,
    'old-run', [{ ...missedFailure.run, status: status.FAILED, revision: 2 }]);
  let recoveryState = { requests: [missedFailure.request], selectedRequestId: 'recovery',
    detail: missedFailure };
  recoveryState = logic.phone_v2_apply_detail(recoveryState, recovered);
  assert.equal(recoveryState.detail.request.currentRunId, 'new-run',
    'complete runs history permits missed terminal recovery');
  let invalidState = { requests: [missedFailure.request], selectedRequestId: 'recovery',
    detail: missedFailure };
  invalidState = logic.phone_v2_apply_detail(invalidState,
    detail(request('recovery', 3, 'new-run', status.QUEUED), 2, 1, 'old-run'));
  assert.equal(invalidState.detail.request.currentRunId, 'old-run',
    'missing previous terminal run must not authorize run switch');

  let summaryOnly = { requests: [request('r1', 3, 'run2', status.QUEUED)],
    selectedRequestId: 'r1', detail: null };
  summaryOnly = logic.phone_v2_apply_created(summaryOnly, request('r1', 0, null, status.WAITING_INPUT));
  summaryOnly = logic.phone_v2_apply_detail(summaryOnly,
    detail(request('r1', 0, null, status.WAITING_INPUT)));
  assert.equal(summaryOnly.requests[0].revision, 3, 'late create must not replace newer summary');
  assert.equal(summaryOnly.detail, null, 'late detail must not repopulate an old run');

  const history = detail(request('history', 1, 'old-run', status.SUCCEEDED));
  state = { requests: [request('r1', 1, 'run1', status.RUNNING), history.request],
    selectedRequestId: 'history', detail: history };
  const activePoll = deferred();
  const onActivePoll = activePoll.promise.then((received) => {
    // PhoneTutorV2Page.refresh_detail updates summaries for nonselected active requests.
    state.requests = logic.phone_v2_merge_requests(state.requests, [received.request]);
  });
  activePoll.resolve(detail(request('r1', 2, 'run1', status.SUCCEEDED), 1, 2));
  await onActivePoll;
  assert.equal(state.detail.request.requestId, 'history', 'history selection must remain visible');
  assert.equal(state.requests[0].status, status.SUCCEEDED, 'active summary must reach terminal state');
  assert.equal(state.requests.some((item) => logic.phone_v2_active(item.status)), false,
    'completed active request must no longer block compose');
  process.stdout.write('Phone V2 async production reducers: 11 assertions passed\n');
}

main().catch((error) => { process.stderr.write(String(error) + '\n'); process.exitCode = 1; })
  .finally(() => { fs.rmSync(temporary, { recursive: true, force: true }); });
