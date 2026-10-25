#!/usr/bin/env node
// Compile the actual ArkTS pure-state sources as TypeScript and exercise their exported rules.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const phone = path.resolve(__dirname, '../src/main/ets/model/PhoneTutorV2Logic.ets');
const back = path.resolve(__dirname, '../src/main/ets/model/PhoneBackController.ets');
const loader = process.env.BETTERLEARNING_ETS_LOADER || '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const common = path.resolve(__dirname, '../../../common/src/main/ets/features/multiagent');
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'betterlearning-phone-v2-'));
try {
  fs.copyFileSync(path.join(common, 'LearningV2Models.ets'), path.join(temporary, 'LearningV2Models.ts'));
  fs.copyFileSync(path.join(common, 'LearningV2State.ets'), path.join(temporary, 'LearningV2State.ts'));
  const source = fs.readFileSync(phone, 'utf8').replace(
    "import { accept_v2_detail, V2TutorRequest, V2TutorRequestDetail, V2TutorRequestStatus } from 'common';",
    "import { V2TutorRequest, V2TutorRequestDetail, V2TutorRequestStatus } from './LearningV2Models';\n" +
    "import { accept_v2_detail } from './LearningV2State';");
  assert.notEqual(source, fs.readFileSync(phone, 'utf8'), 'production import was not substituted');
  fs.writeFileSync(path.join(temporary, 'PhoneTutorV2Logic.ts'), source);
  fs.copyFileSync(back, path.join(temporary, 'PhoneBackController.ts'));
  execFileSync(process.execPath, [path.join(loader,'node_modules/typescript/bin/tsc'), '--module', 'commonjs', '--target', 'es2020', '--outDir', temporary,
    path.join(temporary, 'LearningV2Models.ts'), path.join(temporary, 'LearningV2State.ts'),
    path.join(temporary, 'PhoneTutorV2Logic.ts'), path.join(temporary, 'PhoneBackController.ts')],
  { stdio: 'inherit' });
  const logic = require(path.join(temporary, 'PhoneTutorV2Logic.js'));
  const { V2TutorRequestStatus: status } = require(path.join(temporary, 'LearningV2Models.js'));
  const request = (revision, currentRunId, state) => ({
    requestId: 'r1', questionId: '12', prompt: '为何这样做', mode: 'HINT',
    snapshotInput: { kind: 'NEW_CAPTURE' }, snapshotOriginRequestId: null,
    snapshotFileRef: 's1', currentRunId, status: state, revision,
    createdAt: '2026-10-25T00:00:00Z', updatedAt: '2026-10-25T00:00:00Z'
  });
  const detail = (revision, runId, runRevision = 1, runNo = 1, retryOfRunId = null,
    priorRuns = []) => {
    const run = { runId, requestId: 'r1', runNo, retryOfRunId, status: 'RUNNING',
      revision: runRevision, steps: [] };
    return { request: request(revision, runId, status.RUNNING), run,
      runs: [...priorRuns, run], result: null };
  };
  assert.equal(logic.phone_v2_active(status.WAITING_INPUT), true);
  assert.equal(logic.phone_v2_active(status.CANCELLING), true);
  assert.equal(logic.phone_v2_active(status.SUCCEEDED), false);
  assert.equal(logic.phone_v2_retryable(request(4, 'run1', status.FAILED)), true);
  assert.equal(logic.phone_v2_retryable(request(4, null, status.FAILED)), false);
  assert.equal(logic.phone_v2_accept_selected(detail(3, 'run2'), detail(2, 'run1'), 'r1'), false);
  assert.equal(logic.phone_v2_accept_selected(detail(3, 'run2'), detail(3, 'run1'), 'r1'), false);
  assert.equal(logic.phone_v2_accept_selected(detail(3, 'run2', 5), detail(3, 'run2', 4), 'r1'), false);
  assert.equal(logic.phone_v2_accept_selected(detail(3, 'run2'), detail(4, 'run3'), 'r1'), false);
  const previous = detail(3, 'run2'); previous.request.status = status.FAILED; previous.run.status = 'FAILED';
  const retried = detail(4, 'run3', 1, 2, 'run2', [previous.run]);
  assert.equal(logic.phone_v2_accept_selected(previous, retried, 'r1'), true);
  const missedFailure = detail(2, 'run1');
  const recovered = detail(4, 'run2', 1, 2, 'run1', [
    { ...missedFailure.run, status: 'FAILED', revision: 3 }
  ]);
  assert.equal(logic.phone_v2_accept_selected(missedFailure, recovered, 'r1'), true);
  assert.equal(logic.phone_v2_accept_selected(missedFailure,
    detail(4, 'run2', 1, 2, 'run1'), 'r1'), false);
  assert.equal(logic.phone_v2_accept_selected(null, detail(1, 'run1'), 'other'), false);
  const merged = logic.phone_v2_merge_requests([request(5, 'run2', status.RUNNING)],
    [request(4, 'run1', status.FAILED)]);
  assert.equal(merged[0].revision, 5);
  const older = { ...request(1, 'run0', status.SUCCEEDED), requestId: 'older' };
  assert.equal(logic.phone_v2_merge_requests([older], [request(1, 'run1', status.RUNNING)]).length, 2);
  const intent = new logic.PhoneV2CreateIntent();
  intent.requestId = 'fixed'; intent.prompt = '问题'; intent.mode = 'HINT';
  intent.snapshotKind = 'REUSE_REQUEST'; intent.sourceRequestId = 'source';
  assert.equal(intent.matches('问题', 'HINT', 'REUSE_REQUEST', 'source'), true);
  assert.equal(intent.matches('问题', 'FULL_SOLUTION', 'REUSE_REQUEST', 'source'), false);
  intent.clear();
  assert.equal(intent.requestId, '');
  const accountIntent = logic.phone_v2_intent_for('account1', 'question1');
  accountIntent.requestId = 'same-request';
  assert.equal(logic.phone_v2_intent_for('account1', 'question1').requestId, 'same-request');
  assert.equal(logic.phone_v2_intent_for('account2', 'question1').requestId, '');
  logic.phone_v2_clear_all_intents();
  assert.equal(logic.phone_v2_intent_for('account1', 'question1').requestId, '');
  const { PhoneBackController } = require(path.join(temporary, 'PhoneBackController.js'));
  const backController = new PhoneBackController();
  const backHandler = () => true;
  assert.equal(backController.dispatch(), false);
  backController.register(backHandler);
  assert.equal(backController.dispatch(), true);
  backController.unregister(() => true);
  assert.equal(backController.dispatch(), true);
  backController.unregister(backHandler);
  assert.equal(backController.dispatch(), false);
  process.stdout.write('Phone V2 production pure logic: 24 assertions passed\n');
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}
