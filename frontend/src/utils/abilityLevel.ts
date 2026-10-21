import type { AbilityLevel } from '../types/memory';

const neutral = 'text-slate-600 bg-slate-100';

const levels: Record<AbilityLevel, { label: string; className: string }> = {
  WEAK: { label: '薄弱', className: 'text-rose-700 bg-rose-50' },
  DEVELOPING: { label: '发展中', className: 'text-amber-700 bg-amber-50' },
  COMPETENT: { label: '胜任', className: 'text-sky-700 bg-sky-50' },
  PROFICIENT: { label: '熟练', className: 'text-indigo-700 bg-indigo-50' },
  STRONG: { label: '优秀', className: 'text-emerald-700 bg-emerald-50' },
};

export function abilityLevelDisplay(value: string | null | undefined) {
  return value && Object.prototype.hasOwnProperty.call(levels, value)
    ? levels[value as AbilityLevel]
    : { label: '未知等级', className: neutral };
}

export function proposalStatusDisplay(value: string | null | undefined) {
  switch (value) {
    case 'PROPOSED': return { label: '待确认', className: 'text-amber-700 bg-amber-50', actionable: true };
    case 'ACCEPTED': return { label: '已接受', className: 'text-emerald-700 bg-emerald-50', actionable: false };
    case 'ACCEPTED_WITH_EDIT': return { label: '编辑后接受', className: 'text-sky-700 bg-sky-50', actionable: false };
    case 'REJECTED': return { label: '已拒绝', className: 'text-slate-600 bg-slate-100', actionable: false };
    default: return { label: '未知状态', className: neutral, actionable: false };
  }
}

export function profileStatusDisplay(value: string | null | undefined) {
  switch (value) {
    case 'ACTIVE': return { label: '已启用', className: 'text-emerald-700 bg-emerald-50', action: 'disable' as const };
    case 'DISABLED': return { label: '已停用', className: 'text-slate-600 bg-slate-100', action: 'enable' as const };
    case 'NEEDS_REVIEW': return { label: '需要复核', className: 'text-amber-700 bg-amber-50', action: 'disable' as const };
    default: return { label: '未知状态', className: neutral, action: null };
  }
}
