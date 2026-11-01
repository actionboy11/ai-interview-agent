import { useEffect, useRef, useState } from 'react';
import { HelpCircle, Loader2, RefreshCw, Save, Send, SkipForward } from 'lucide-react';
import { useDynamicInterview } from '../hooks/useDynamicInterview';
import { interviewApi } from '../api/interview';
import type { InterviewSession } from '../types/interview';
import type { SubmissionIntent } from '../types/dynamicInterview';
import { mainProgress } from '../utils/dynamicInterviewState';
import ConfirmDialog from './ConfirmDialog';

export default function DynamicInterviewPanel({ session, onBack, onComplete }: {
  session: InterviewSession; onBack: () => void; onComplete: () => void;
}) {
  const flow = useDynamicInterview(session.sessionId);
  const { runtime } = flow;
  const [transcript, setTranscript] = useState(session.questions);
  const completed = useRef(false);
  const [confirm, setConfirm] = useState<SubmissionIntent | 'COMPLETE' | null>(null);
  useEffect(() => {
    const abort = new AbortController();
    interviewApi.getSession(session.sessionId, abort.signal).then(value => {
      if (!abort.signal.aborted) setTranscript(value.questions);
    }).catch(() => { /* Runtime remains authoritative; transcript refresh can retry on next version. */ });
    return () => abort.abort();
  }, [session.sessionId, runtime?.version]);
  useEffect(() => {
    if (runtime?.phase === 'COMPLETED' && !completed.current) { completed.current = true; onComplete(); }
  }, [runtime?.phase, onComplete]);
  const progress = runtime ? mainProgress(runtime) : null;
  return (
    <div className="mx-auto max-w-4xl space-y-5 pb-10">
      <div className="flex items-center justify-between gap-3">
        <h1 className="text-2xl font-bold text-slate-900 dark:text-white">动态模拟面试</h1>
        <button onClick={onBack} className="text-sm text-primary-600">返回</button>
      </div>
      {progress && <div className="rounded-xl bg-white p-4 dark:bg-slate-800">
        <p>主问题已完成 {progress.completed} / {progress.planned} · 当前第 {progress.current} 道主问题</p>
        <p className="mt-1 text-sm text-slate-500">已发布 {runtime!.totalQuestions} 个作答项 · 已追问 {runtime!.usedFollowUps} 次</p>
      </div>}
      {flow.error && <div role="alert" className="rounded-xl bg-amber-50 p-4 text-amber-900">
        <p>{flow.error}</p>
        <div className="mt-2 flex gap-4">
          <button onClick={() => void flow.refresh()} className="inline-flex items-center gap-1"><RefreshCw className="h-4 w-4" />恢复查询</button>
          {flow.uncertain && <button disabled={flow.busy} onClick={() => void flow.retry()}>安全重试原提交</button>}
        </div>
      </div>}
      {flow.uncertain && !flow.error && <p role="status" className="text-amber-700">正在确认上次提交结果，请恢复查询或安全重试原提交。
        <button disabled={flow.busy} onClick={() => void flow.retry()} className="ml-3 underline">安全重试原提交</button></p>}
      {transcript.filter(q => q.submissionIntent != null).map(q => <article key={q.questionIndex} className="rounded-xl bg-white p-5 dark:bg-slate-800">
        <p className="font-medium">{q.question}</p>
        <p className="mt-2 whitespace-pre-wrap text-slate-600 dark:text-slate-300">{q.submissionIntent === 'DONT_KNOW' ? '我不会' : q.submissionIntent === 'SKIP' ? '已跳过本题' : q.userAnswer}</p>
      </article>)}
      {!runtime && <p role="status" className="flex items-center gap-2"><Loader2 className="h-4 w-4 animate-spin" />正在恢复面试进度…</p>}
      {runtime?.noticeCode && <p role="status" className="rounded-xl bg-amber-50 p-4 text-amber-900">本轮追问处理未完成，答案已保留，已进入下一主问题；系统故障不会扣分。</p>}
      {runtime?.phase === 'DECISION_PENDING' && <div role="status" className="rounded-xl bg-blue-50 p-5 text-blue-900">
        <p className="flex items-center gap-2"><Loader2 className="h-4 w-4 animate-spin" />答案已提交，正在决定是否追问…</p>
        <p className="mt-2 text-sm">可离开后继续，也可提前交卷。</p>
      </div>}
      {runtime?.phase === 'WAITING_ANSWER' && runtime.currentQuestion && <div className="rounded-xl bg-white p-5 dark:bg-slate-800">
        <p className="mb-2 text-sm text-primary-600">{runtime.currentQuestion.isFollowUp ? '追问' : '主问题'} · {runtime.currentQuestion.category}</p>
        <h2 className="mb-5 whitespace-pre-wrap text-lg font-semibold">{runtime.currentQuestion.question}</h2>
        <AnswerForm key={runtime.currentQuestion.questionIndex} initial={runtime.currentQuestion.userAnswer ?? ''}
          disabled={!flow.canAnswer} busy={flow.busy} onSubmit={answer => void flow.submit('ANSWER', answer)}
          onSave={answer => void flow.saveDraft(answer)} draftStatus={flow.draftStatus} />
        <div className="mt-3 flex flex-wrap gap-4">
          <button disabled={!flow.canAnswer} onClick={() => setConfirm('DONT_KNOW')} className="inline-flex items-center gap-1 text-amber-700 disabled:opacity-50"><HelpCircle className="h-4 w-4" />我不会</button>
          <button disabled={!flow.canAnswer} onClick={() => setConfirm('SKIP')} className="inline-flex items-center gap-1 text-slate-500 disabled:opacity-50"><SkipForward className="h-4 w-4" />跳过本题</button>
        </div>
      </div>}
      {runtime && runtime.phase !== 'COMPLETED' && <button disabled={flow.busy || flow.uncertain} onClick={() => setConfirm('COMPLETE')} className="rounded-lg border border-amber-300 px-4 py-2 text-amber-700 disabled:opacity-50">提前交卷</button>}
      <ConfirmDialog open={confirm !== null} title={confirm === 'COMPLETE' ? '提前交卷' : confirm === 'DONT_KNOW' ? '确认我不会' : '确认跳过本题'}
        message={confirm === 'COMPLETE' ? '确定交卷吗？未获得证据的主问题将显示未评估，已提交答案保留。' : confirm === 'DONT_KNOW' ? '将记录为明确不会并结束当前主问题组，提交后不可修改。' : '将记录为跳过并结束当前主问题组，提交后不可修改。'}
        loading={flow.busy} onCancel={() => setConfirm(null)} onConfirm={() => {
          if (confirm === 'COMPLETE') void flow.complete();
          else if (confirm) void flow.submit(confirm, '');
          setConfirm(null);
        }} />
    </div>
  );
}

function AnswerForm({ initial, disabled, busy, onSubmit, onSave, draftStatus }: {
  initial: string; disabled: boolean; busy: boolean; onSubmit: (answer: string) => void;
  onSave: (answer: string) => void; draftStatus: string;
}) {
  const [answer, setAnswer] = useState(initial);
  return <div>
    <label className="block text-sm">你的回答<textarea rows={6} value={answer} disabled={disabled}
      onChange={event => setAnswer(event.target.value)} className="mt-2 w-full rounded-xl border border-slate-300 bg-transparent p-3 disabled:opacity-60" /></label>
    <div className="mt-3 flex flex-wrap items-center gap-3">
      <button disabled={disabled || !answer.trim()} onClick={() => onSubmit(answer)} className="inline-flex items-center gap-1 rounded-lg bg-primary-600 px-4 py-2 text-white disabled:opacity-50"><Send className="h-4 w-4" />{busy ? '正在提交…' : '提交回答'}</button>
      <button disabled={disabled || draftStatus === 'saving'} onClick={() => onSave(answer)} className="inline-flex items-center gap-1 text-primary-600 disabled:opacity-50"><Save className="h-4 w-4" />保存草稿</button>
      {draftStatus === 'saved' && <span role="status" className="text-sm text-green-700">草稿已保存</span>}
    </div>
  </div>;
}
