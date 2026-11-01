import test from 'node:test';
import assert from 'node:assert/strict';
import { averageValidScore, formatScore, getScoreColor } from './score.ts';

test('unknownScoreNotZero: unknown is unevaluated, real zero remains zero', () => {
  assert.equal(formatScore(null), '未评估');
  assert.equal(formatScore(undefined), '未评估');
  assert.equal(formatScore(0), '0');
  assert.notEqual(getScoreColor(null), getScoreColor(0));
});
test('history average excludes unknown scores and includes real zero', () => {
  assert.equal(averageValidScore([null, 0, 80, undefined]), 40);
  assert.equal(averageValidScore([null, undefined]), null);
  assert.equal(averageValidScore([0]), 0);
});
