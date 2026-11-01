import test from 'node:test';
import assert from 'node:assert/strict';
import { DynamicInterviewController, acceptSnapshot, mainProgress } from './dynamicInterviewState.ts';
import type { DynamicRuntimeSnapshot, DynamicSubmissionReceipt, DynamicSubmitRequest } from '../types/dynamicInterview.ts';
import request, { clearCsrfToken, getResultError } from '../api/request.ts';

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

test('明确400拒绝解除持久锁，查询保留提示，刷新恢复后可修改答案或结束当前题', async () => {
  for (const recovery of ['ANSWER', 'DONT_KNOW', 'SKIP', 'COMPLETE'] as const) {
    clearCsrfToken();
    let stored: DynamicSubmitRequest | null = null;
    const sent: DynamicSubmitRequest[] = [];
    let completions = 0;
    request.getInstance().defaults.adapter = async config => {
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      let data: unknown;
      let code = 200;
      let message = 'success';
      if (config.url === '/api/auth/csrf') data = { headerName: 'X-CSRF', token: 'synthetic' };
      else {
        sent.push(body);
        if (body.answer.length > 20000) { code = 400; message = '答案长度超过允许上限'; data = null; }
        else data = { requestId: body.requestId, decisionId: 'd1', runtime: snapshot({ version: 5, phase: 'DECISION_PENDING' }) };
      }
      return { data: { code, message, data }, status: 200, statusText: 'OK', headers: {}, config };
    };
    const api = { ...fixture().controller.api,
      submit: (_id: string, body: DynamicSubmitRequest) => request.post<DynamicSubmissionReceipt>('/synthetic/answers', body),
      complete: async () => { completions += 1; return snapshot({ version: 6, phase: 'COMPLETED' }); },
    };
    const store = { read: () => stored, write: (body: DynamicSubmitRequest | null) => { stored = body; } };
    const first = new DynamicInterviewController('s1', api, () => 'rejected-id', store);
    await first.refresh();
    await first.submit('ANSWER', 'x'.repeat(20001));
    assert.equal(first.canAnswer(), true);
    assert.equal(first.getSnapshot().uncertain, false);
    assert.equal(stored, null);
    await first.refresh();
    assert.match(first.getSnapshot().error, /答案长度超过允许上限/);
    first.dispose();
    const restored = new DynamicInterviewController('s1', api, () => 'corrected-id', store);
    await restored.refresh();
    assert.equal(restored.canAnswer(), true);
    if (recovery === 'COMPLETE') {
      await restored.complete();
      assert.equal(completions, 1);
      assert.equal(restored.getSnapshot().runtime?.phase, 'COMPLETED');
    } else {
      await restored.submit(recovery, recovery === 'ANSWER' ? '改短后的回答' : '');
      assert.deepEqual(sent[1], { requestId: 'corrected-id', questionIndex: 7, expectedVersion: 4,
        submissionIntent: recovery, answer: recovery === 'ANSWER' ? '改短后的回答' : '' });
      assert.equal(restored.getSnapshot().runtime?.phase, 'DECISION_PENDING');
    }
    restored.dispose();
  }
});

test('500与未知业务错误以及断网跨刷新继续保留原提交，不按错误文案判断', async () => {
  for (const failure of [getResultError({ code: 500, message: '答案长度超过允许上限', data: null }),
    getResultError({ code: 19999, message: '答案长度超过允许上限', data: null }), new Error('网络连接失败')]) {
    let stored: DynamicSubmitRequest | null = null;
    const sent: DynamicSubmitRequest[] = [];
    let fail = true;
    const api = { ...fixture().controller.api, submit: async (_id: string, body: DynamicSubmitRequest) => {
      sent.push(structuredClone(body));
      if (fail) throw failure;
      return { requestId: body.requestId, decisionId: 'd1', runtime: snapshot({ version: 5, phase: 'DECISION_PENDING' }) };
    } };
    const store = { read: () => stored, write: (body: DynamicSubmitRequest | null) => { stored = body; } };
    const first = new DynamicInterviewController('s1', api, () => 'original-id', store);
    await first.refresh(); await first.submit('ANSWER', '原始正文');
    assert.equal(first.canAnswer(), false);
    assert.equal(first.getSnapshot().uncertain, true);
    first.dispose();
    const restored = new DynamicInterviewController('s1', api, () => 'must-not-use-new-id', store);
    await restored.refresh();
    assert.equal(restored.canAnswer(), false);
    fail = false;
    await restored.retry();
    assert.deepEqual(sent[1], sent[0]);
    assert.equal(stored, null);
    restored.dispose();
  }
});
