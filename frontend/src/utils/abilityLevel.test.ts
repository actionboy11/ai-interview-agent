import test from 'node:test';
import assert from 'node:assert/strict';
import { abilityLevelDisplay, profileStatusDisplay, proposalStatusDisplay } from './abilityLevel.ts';

test('所有后端能力等级都有中文标签和颜色', () => {
  const expected = [
    ['WEAK', '薄弱'],
    ['DEVELOPING', '发展中'],
    ['COMPETENT', '胜任'],
    ['PROFICIENT', '熟练'],
    ['STRONG', '优秀'],
  ] as const;
  for (const [value, label] of expected) {
    const display = abilityLevelDisplay(value);
    assert.equal(display.label, label);
    assert.match(display.className, /text-/);
  }
});

test('只有待确认候选可以操作，未知状态安全降级', () => {
  const expected = [
    ['PROPOSED', '待确认', true],
    ['ACCEPTED', '已接受', false],
    ['ACCEPTED_WITH_EDIT', '编辑后接受', false],
    ['REJECTED', '已拒绝', false],
  ] as const;
  for (const [value, label, actionable] of expected) {
    const display = proposalStatusDisplay(value);
    assert.equal(display.label, label);
    assert.equal(display.actionable, actionable);
    assert.match(display.className, /text-/);
  }
  assert.deepEqual(proposalStatusDisplay('FUTURE'), {
    label: '未知状态', className: 'text-slate-600 bg-slate-100', actionable: false,
  });
});

test('画像启停和复核状态映射明确，未知状态不能操作', () => {
  assert.equal(profileStatusDisplay('ACTIVE').action, 'disable');
  assert.equal(profileStatusDisplay('DISABLED').action, 'enable');
  assert.equal(profileStatusDisplay('NEEDS_REVIEW').label, '需要复核');
  assert.equal(profileStatusDisplay('NEEDS_REVIEW').action, 'disable');
  assert.equal(profileStatusDisplay('FUTURE').action, null);
  assert.equal(abilityLevelDisplay('FUTURE').label, '未知等级');
});
