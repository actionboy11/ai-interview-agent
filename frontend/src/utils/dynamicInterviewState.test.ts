import test from 'node:test';
import assert from 'node:assert/strict';
import { DynamicInterviewController, acceptSnapshot, mainProgress } from './dynamicInterviewState.ts';
import type { DynamicRuntimeSnapshot, DynamicSubmitRequest } from '../types/dynamicInterview.ts';

const snapshot = (patch: Partial<DynamicRuntimeSnapshot> = {}): DynamicRuntimeSnapshot => ({
  sessionId: 's1', version: 4, phase: 'WAITING_ANSWER', plannedMainQuestionCount: 3,
  currentMainOrdinal: 1, completedMainCount: 0, usedFollowUps: 0, totalQuestions: 1,
  mainQuestionCount: 1, followUpQuestionCount: 0, pendingDecisionId: null, noticeCode: null,
  currentQuestion: { questionIndex: 7, question: 'Q', type: 'TECHNICAL', category: 'Java',
    userAnswer: null, score: null, feedback: null }, ...patch,
});
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
};
function fixture() {
  const sent: DynamicSubmitRequest[] = [];
  let next = snapshot();
  let fail = false;
  const drafts = deferred<void>();
  const controller = new DynamicInterviewController('s1', {
    get: async () => next,
    submit: async (_id, body) => {
      sent.push(structuredClone(body));
      if (fail) throw new Error('disconnected');
      return { requestId: body.requestId, decisionId: 'd1', runtime: snapshot({ version: 5, phase: 'DECISION_PENDING' }) };
    },
    saveDraft: () => drafts.promise,
    complete: async () => snapshot({ version: 9, phase: 'COMPLETED', currentQuestion: null }),
  }, () => '00000000-0000-4000-8000-000000000001');
  return { controller, sent, drafts, setNext: (value: DynamicRuntimeSnapshot) => { next = value; }, setFail: (value: boolean) => { fail = value; } };
}

test('pendingIsNotCompleted: published question count does not complete the session', async () => {
  const { controller } = fixture();
  await controller.refresh();
  await controller.submit('ANSWER', 'answer');
  assert.equal(controller.getSnapshot().runtime?.phase, 'DECISION_PENDING');
  assert.equal(controller.canAnswer(), false);
});
test('lateLowerVersionIgnored and foreign sessions never replace current runtime', () => {
  const current = snapshot({ version: 9 });
  assert.equal(acceptSnapshot('s1', current, snapshot()), current);
  assert.equal(acceptSnapshot('s1', current, snapshot({ sessionId: 's2', version: 20 })), current);
});
test('retryKeepsRequestId and exact payload after uncertain network outcome', async () => {
  const f = fixture();
  await f.controller.refresh();
  f.setFail(true);
  await f.controller.submit('ANSWER', ' original ');
  assert.equal(f.controller.canAnswer(), false);
  assert.equal(f.controller.getSnapshot().uncertain, true);
  await f.controller.submit('SKIP', 'changed');
  assert.equal(f.sent.length, 1);
  f.setFail(false);
  await f.controller.retry();
  assert.equal(f.sent.length, 2);
  assert.deepEqual(f.sent[1], { requestId: '00000000-0000-4000-8000-000000000001',
    questionIndex: 7, expectedVersion: 4, submissionIntent: 'ANSWER', answer: 'original' });
  assert.deepEqual(f.sent[0], f.sent[1]);
});
test('sessionChangeDropsOldPoll: disposed controller ignores late inflight snapshot', async () => {
  const f = fixture();
  const waiting = deferred<DynamicRuntimeSnapshot>();
  const c = new DynamicInterviewController('s1', { ...f.controller.api, get: () => waiting.promise });
  const request = c.refresh();
  c.dispose();
  waiting.resolve(snapshot());
  await request;
  assert.equal(c.getSnapshot().runtime, null);
});
test('dontKnowAndSkipSendDistinctIntents and retain empty bodies', async () => {
  for (const intent of ['DONT_KNOW', 'SKIP'] as const) {
    const f = fixture();
    await f.controller.refresh();
    await f.controller.submit(intent, '');
    assert.equal(f.sent[0].submissionIntent, intent);
    assert.equal(f.sent[0].answer, '');
  }
});
test('mainProgressNotAnswerCount: follow-ups do not inflate completed main progress', () => {
  assert.deepEqual(mainProgress(snapshot({ completedMainCount: 1, currentMainOrdinal: 2,
    totalQuestions: 5, usedFollowUps: 3 })), { completed: 1, planned: 3, current: 2 });
});
test('draftLateWriteGuard: late draft response cannot unlock formal submission', async () => {
  const f = fixture();
  await f.controller.refresh();
  const draft = f.controller.saveDraft('draft');
  await f.controller.submit('ANSWER', 'formal');
  f.drafts.resolve();
  await draft;
  assert.equal(f.controller.canAnswer(), false);
  assert.equal(f.controller.getSnapshot().runtime?.phase, 'DECISION_PENDING');
  assert.notEqual(f.controller.getSnapshot().draftStatus, 'saved');
});
test('recovery refresh recognizes committed answer and does not replay it', async () => {
  const f = fixture();
  await f.controller.refresh();
  f.setFail(true);
  await f.controller.submit('ANSWER', 'original');
  f.setNext(snapshot({ version: 6, currentMainOrdinal: 2, completedMainCount: 1,
    currentQuestion: { ...snapshot().currentQuestion!, questionIndex: 12 } }));
  await f.controller.refresh();
  await f.controller.retry();
  assert.equal(f.sent.length, 1);
  assert.equal(f.controller.canAnswer(), true);
});
test('complete while pending uses server version and phase only', async () => {
  const f = fixture();
  await f.controller.refresh();
  await f.controller.submit('ANSWER', 'a');
  await f.controller.complete();
  assert.equal(f.controller.getSnapshot().runtime?.phase, 'COMPLETED');
});
test('polling waits two seconds, stops on completion and cancels on unmount', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const f = fixture();
  let gets = 0;
  const c = new DynamicInterviewController('s1', { ...f.controller.api,
    get: async () => { gets += 1; return snapshot({ phase: gets === 3 ? 'COMPLETED' : 'DECISION_PENDING' }); } });
  c.startPolling();
  await Promise.resolve(); await Promise.resolve();
  assert.equal(gets, 1);
  t.mock.timers.tick(1999);
  assert.equal(gets, 1);
  t.mock.timers.tick(1);
  await Promise.resolve(); await Promise.resolve();
  assert.equal(gets, 2);
  t.mock.timers.tick(2000);
  await Promise.resolve(); await Promise.resolve();
  assert.equal(gets, 3);
  t.mock.timers.tick(10000);
  assert.equal(gets, 3);
  c.dispose();
});
test('refresh restoration reuses persisted pending request before any new intent', async () => {
  const f = fixture();
  const body: DynamicSubmitRequest = { requestId: 'persisted-request', questionIndex: 7,
    expectedVersion: 4, submissionIntent: 'DONT_KNOW', answer: '' };
  let stored: DynamicSubmitRequest | null = body;
  const c = new DynamicInterviewController('s1', f.controller.api, () => 'wrong-new-id',
    { read: () => stored, write: value => { stored = value; } });
  await c.refresh();
  assert.equal(c.canAnswer(), false);
  await c.retry();
  assert.deepEqual(f.sent[0], body);
  assert.equal(stored, null);
});
