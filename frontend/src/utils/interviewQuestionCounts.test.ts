import test from 'node:test';
import assert from 'node:assert/strict';
import {
  formatInterviewQuestionCounts,
  getInterviewFollowUpLabel,
} from './interviewQuestionCounts.ts';

test('题数来自实际主问题和追问计数，不假设每题一个追问', () => {
  const cases = [
    [{ totalQuestions: 12, mainQuestionCount: 6, followUpQuestionCount: 6 }, '6道主问题 + 6道追问，共12个作答项'],
    [{ totalQuestions: 16, mainQuestionCount: 8, followUpQuestionCount: 8 }, '8道主问题 + 8道追问，共16个作答项'],
    [{ totalQuestions: 6, mainQuestionCount: 6, followUpQuestionCount: 0 }, '6道主问题 + 0道追问，共6个作答项'],
    [{ totalQuestions: 18, mainQuestionCount: 6, followUpQuestionCount: 12 }, '6道主问题 + 12道追问，共18个作答项'],
    [{ totalQuestions: 10, mainQuestionCount: 6, followUpQuestionCount: 4 }, '6道主问题 + 4道追问，共10个作答项'],
    [{ totalQuestions: 0, mainQuestionCount: 0, followUpQuestionCount: 0 }, '0道主问题 + 0道追问，共0个作答项'],
  ] as const;
  for (const [input, expected] of cases) {
    assert.equal(formatInterviewQuestionCounts(input), expected);
  }
});

test('旧响应和未知细分仅显示总作答项，真实零不当成未知', () => {
  const cases = [
    [{ totalQuestions: 12 }, '12个作答项'],
    [{ totalQuestions: 12, mainQuestionCount: null, followUpQuestionCount: null }, '12个作答项'],
    [{ totalQuestions: 12, mainQuestionCount: 6 }, '12个作答项'],
    [{ totalQuestions: 12, followUpQuestionCount: 6 }, '12个作答项'],
    [{ totalQuestions: 0 }, '0个作答项'],
    [{}, '题数未知'],
    [{ totalQuestions: null, mainQuestionCount: null, followUpQuestionCount: undefined }, '题数未知'],
    [{ mainQuestionCount: 6, followUpQuestionCount: 6 }, '题数未知'],
  ] as const;
  for (const [input, expected] of cases) {
    assert.equal(formatInterviewQuestionCounts(input), expected);
  }
});

test('非法计数或细分与总数矛盾时降级，不展示不可信细分', () => {
  for (const invalid of [-1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal(formatInterviewQuestionCounts({
      totalQuestions: 12, mainQuestionCount: invalid, followUpQuestionCount: 6,
    }), '12个作答项');
    assert.equal(formatInterviewQuestionCounts({
      totalQuestions: 12, mainQuestionCount: 6, followUpQuestionCount: invalid,
    }), '12个作答项');
    assert.equal(formatInterviewQuestionCounts({
      totalQuestions: invalid, mainQuestionCount: 6, followUpQuestionCount: 6,
    }), '题数未知');
  }
  assert.equal(formatInterviewQuestionCounts({
    totalQuestions: 12, mainQuestionCount: 6, followUpQuestionCount: 8,
  }), '12个作答项');
});

test('按显式父题索引匹配非连续题号，两个追问可以属于同一道主问题', () => {
  const questions = [
    { questionIndex: 8, isFollowUp: true, parentQuestionIndex: 2 },
    { questionIndex: 2, isFollowUp: false, parentQuestionIndex: null },
    { questionIndex: 15, isFollowUp: false, parentQuestionIndex: null },
    { questionIndex: 11, isFollowUp: true, parentQuestionIndex: 2 },
    { questionIndex: 19, isFollowUp: true, parentQuestionIndex: 15 },
  ];
  const original = structuredClone(questions);
  assert.equal(getInterviewFollowUpLabel(8, questions), '第3道主问题的追问');
  assert.equal(getInterviewFollowUpLabel(11, questions), '第3道主问题的追问');
  assert.equal(getInterviewFollowUpLabel(19, questions), '第16道主问题的追问');
  assert.equal(getInterviewFollowUpLabel(2, questions), null);
  assert.deepEqual(questions, original);
});

test('旧问题缺少元数据或父题关系无效时不猜测奇偶配对', () => {
  assert.equal(getInterviewFollowUpLabel(1, [{ questionIndex: 0 }, { questionIndex: 1 }]), null);
  assert.equal(getInterviewFollowUpLabel(1, undefined), null);
  for (const parentQuestionIndex of [null, undefined, -1, 0.5, 9, 1]) {
    assert.equal(getInterviewFollowUpLabel(1, [
      { questionIndex: 0, isFollowUp: false },
      { questionIndex: 1, isFollowUp: true, parentQuestionIndex },
    ]), null);
  }
  assert.equal(getInterviewFollowUpLabel(1, [
    { questionIndex: 0, isFollowUp: true },
    { questionIndex: 1, isFollowUp: true, parentQuestionIndex: 0 },
  ]), null);
  assert.equal(getInterviewFollowUpLabel(1, [
    { questionIndex: 0 },
    { questionIndex: 1, isFollowUp: true, parentQuestionIndex: 0 },
  ]), null);
});
