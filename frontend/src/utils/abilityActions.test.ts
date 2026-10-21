import test from 'node:test';
import assert from 'node:assert/strict';
import { proposalActions } from './abilityActions.ts';

test('候选证据请求失败时仍可拒绝，但不可接受', () => {
  assert.deepEqual(proposalActions('PROPOSED', 0, true, false, false, true, 0, false), {
    accept: false, reject: true,
  });
});

test('候选证据为空时仍可拒绝，但不可接受', () => {
  assert.deepEqual(proposalActions('PROPOSED', 0, true, false, false, false, 0, false), {
    accept: false, reject: true,
  });
});

test('已处理或非当前会话的候选不可重复操作', () => {
  assert.deepEqual(proposalActions('ACCEPTED', 1, true, false, false, false, 3, true), {
    accept: false, reject: false,
  });
  assert.deepEqual(proposalActions('PROPOSED', 0, false, false, false, false, 3, true), {
    accept: false, reject: false,
  });
  assert.deepEqual(proposalActions('PROPOSED', NaN, true, false, false, false, 3, true), {
    accept: false, reject: false,
  });
});
