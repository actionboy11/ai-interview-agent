import type {QuestionPlanSummary, QuestionPurpose} from '../types/interview';

const purposes: Array<[QuestionPurpose, string]> = [
  ['MANDATORY', '必考'],
  ['BASELINE', '基础覆盖'],
  ['WEAKNESS_REINFORCEMENT', '薄弱项巩固'],
  ['EXPLORATION', '探索'],
  ['STRENGTH_CALIBRATION', '优势校准'],
];

export default function QuestionPlanExplanation({plan, loading, error, onRetry, questionIndex}: {
  plan: QuestionPlanSummary | null;
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  questionIndex?: number;
}) {
  return (
    <section className="rounded-2xl bg-white p-5 shadow-sm dark:bg-slate-800" aria-label="出题说明">
      <div className="flex items-center justify-between gap-3">
        <h3 className="font-semibold text-slate-900 dark:text-white">出题说明</h3>
        {error && <button type="button" onClick={onRetry} className="text-sm text-primary-600 hover:underline">重试</button>}
      </div>
      {loading && <p className="mt-2 text-sm text-slate-500">正在加载出题说明…</p>}
      {error && <p role="alert" className="mt-2 text-sm text-rose-600">{error}</p>}
      {!loading && !error && !plan?.available && (
        <p className="mt-2 text-sm text-slate-500">这场面试没有保存出题计划，暂无说明。</p>
      )}
      {!loading && !error && plan?.available && (
        <>
          <p className="mt-2 text-sm text-slate-600 dark:text-slate-300">
            {plan.mode === 'ADAPTIVE' ? '结合已确认的能力画像安排题目' : '按固定题量覆盖面试方向'}
          </p>
          <div className="mt-3 flex flex-wrap gap-2 text-xs">
            {purposes.filter(([purpose]) => (plan.purposeCounts[purpose] ?? 0) > 0)
              .map(([purpose, label]) => (
                <span key={purpose} className="rounded-full bg-primary-50 px-3 py-1 text-primary-700 dark:bg-primary-900/30 dark:text-primary-300">
                  {label} {plan.purposeCounts[purpose]}
                </span>
              ))}
          </div>
          {plan.questions.length > 0 && (
            <ol className="mt-4 space-y-2 text-sm text-slate-600 dark:text-slate-300">
              {plan.questions.filter(question => questionIndex === undefined
                || question.questionIndex === questionIndex).map(question => (
                <li key={`${question.questionIndex}-${question.slotId}`}>
                  第 {question.questionIndex + 1} 题 · {question.categoryKey}：{question.reason}
                </li>
              ))}
            </ol>
          )}
        </>
      )}
    </section>
  );
}
