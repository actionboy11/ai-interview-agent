/**
 * 分数计算和归一化工具函数
 */

/**
 * 计算百分比
 * @param score 当前分数
 * @param maxScore 满分
 * @returns 百分比 (0-100)
 */
export function calculatePercentage(score: number | null, maxScore: number): number {
  if (score == null) return 0;
  if (maxScore === 0) return 0;
  return Math.round((score / maxScore) * 100);
}

/**
 * 归一化分数到指定满分
 * @param score 原始分数
 * @param originalMax 原始满分
 * @param targetMax 目标满分
 * @returns 归一化后的分数
 */
export function normalizeScore(
  score: number,
  originalMax: number,
  targetMax: number
): number {
  if (originalMax === 0) return 0;
  return (score / originalMax) * targetMax;
}

/**
 * 检测是否为深色模式
 */
function isDarkMode(): boolean {
  return typeof window !== 'undefined' && document.documentElement.classList.contains('dark');
}

/**
 * 根据分数获取颜色类名
 * @param score 分数
 * @param thresholds 阈值配置，默认 [85, 70]
 * @returns Tailwind CSS 颜色类名
 */
export function getScoreColor(
  score: number | null | undefined,
  thresholds: [number, number] = [80, 70]
): string {
  const dark = isDarkMode();
  if (score == null) return 'bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-400';

  if (dark) {
    // 深色模式：使用更柔和的颜色
    if (score >= thresholds[0]) return 'bg-emerald-500/20 text-emerald-400';
    if (score >= thresholds[1]) return 'bg-yellow-500/20 text-yellow-400';
    return 'bg-red-500/20 text-red-400';
  }

  // 浅色模式
  if (score >= thresholds[0]) return 'bg-emerald-100 text-emerald-600';
  if (score >= thresholds[1]) return 'bg-amber-100 text-amber-600';
  return 'bg-red-100 text-red-600';
}

/**
 * 根据分数获取进度条颜色类名（用于进度条）
 */
export function getScoreProgressColor(
  score: number | null,
  thresholds: [number, number] = [80, 70]
): string {
  if (score == null) return 'bg-slate-300';
  if (score >= thresholds[0]) return 'bg-emerald-500';
  if (score >= thresholds[1]) return 'bg-amber-500';
  return 'bg-red-500';
}

/**
 * 根据分数获取文本颜色类名
 */
export function getScoreTextColor(
  score: number | null,
  thresholds: [number, number] = [80, 70]
): string {
  if (score == null) return 'text-slate-500';
  if (score >= thresholds[0]) return 'text-emerald-500';
  if (score >= thresholds[1]) return 'text-amber-500';
  return 'text-red-500';
}

export function formatScore(score: number | null | undefined): string {
  return score == null ? '未评估' : String(score);
}

export function averageValidScore(scores: Array<number | null | undefined>): number | null {
  const valid = scores.filter((score): score is number => typeof score === 'number' && Number.isFinite(score));
  return valid.length ? Math.round(valid.reduce((sum, score) => sum + score, 0) / valid.length) : null;
}
