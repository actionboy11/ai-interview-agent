import { useEffect, useState } from 'react';
import { Check, ExternalLink, Pencil, RefreshCw, X } from 'lucide-react';
import { Link } from 'react-router-dom';
import { memoryApi } from '../api/memory';
import { getErrorMessage } from '../api/request';
import { ROUTES } from '../constants/routes';
import type { AbilityEvidence, AbilityLevel, AbilityProposal } from '../types/memory';
import { abilityLevelDisplay, proposalStatusDisplay } from '../utils/abilityLevel';

const levels: AbilityLevel[] = ['WEAK', 'DEVELOPING', 'COMPETENT', 'PROFICIENT', 'STRONG'];

interface Props {
  proposal: AbilityProposal;
  sessionId: string;
  onDecision: () => Promise<void>;
}

export default function AbilityProposalCard({ proposal, sessionId, onDecision }: Props) {
  const [evidence, setEvidence] = useState<AbilityEvidence[]>([]);
  const [evidenceLoading, setEvidenceLoading] = useState(true);
  const [evidenceError, setEvidenceError] = useState('');
  const [editing, setEditing] = useState(false);
  const [level, setLevel] = useState<AbilityLevel | ''>(levels.includes(proposal.proposedLevel) ? proposal.proposedLevel : '');
  const [summary, setSummary] = useState(proposal.proposedSummary);
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const loadEvidence = () => {
    setEvidenceLoading(true);
    setEvidenceError('');
    memoryApi.proposalEvidence(proposal.id)
      .then(items => setEvidence(items))
      .catch(err => setEvidenceError(getErrorMessage(err)))
      .finally(() => setEvidenceLoading(false));
  };

  useEffect(() => {
    let active = true;
    setEvidence([]);
    setEvidenceLoading(true);
    setEvidenceError('');
    memoryApi.proposalEvidence(proposal.id)
      .then(items => { if (active) setEvidence(items); })
      .catch(err => { if (active) setEvidenceError(getErrorMessage(err)); })
      .finally(() => { if (active) setEvidenceLoading(false); });
    return () => { active = false; };
  }, [proposal.id]);

  const status = proposalStatusDisplay(proposal.status);
  const isCurrentSession = proposal.interviewSessionId === sessionId;
  const lowEvidence = evidence.length < 3;
  const canAct = status.actionable && isCurrentSession && !busy && !evidenceLoading && !evidenceError && evidence.length > 0;

  const decide = async (action: 'accept' | 'edit' | 'reject') => {
    if (!canAct || ((action === 'accept' || action === 'edit') && lowEvidence && !confirmed)) return;
    if (action === 'edit' && (!level || !summary.trim() || summary.length > 2000)) {
      setError('请选择等级，并填写不超过 2000 字的摘要');
      return;
    }
    setBusy(true);
    setError('');
    try {
      if (action === 'accept') await memoryApi.accept(proposal.id, proposal.version);
      if (action === 'edit') await memoryApi.acceptWithEdit(proposal.id, proposal.version, level as AbilityLevel, summary.trim());
      if (action === 'reject') await memoryApi.reject(proposal.id, proposal.version);
      await onDecision();
    } catch (err) {
      setError(`${getErrorMessage(err)}。请刷新候选后重试。`);
      await onDecision().catch(() => undefined);
    } finally {
      setBusy(false);
    }
  };

  return (
    <article className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h4 className="font-semibold text-slate-900 dark:text-white">{proposal.skillId} · {proposal.categoryKey}</h4>
          <Link className="mt-1 inline-flex items-center gap-1 text-sm text-primary-600 hover:underline" to={ROUTES.interviewDetail(proposal.interviewSessionId)}>
            来源面试 <ExternalLink className="h-3.5 w-3.5" />
          </Link>
        </div>
        <span className={`rounded-full px-3 py-1 text-xs font-medium ${status.className}`}>{status.label}</span>
      </div>

      <div className="mt-4 grid gap-3 sm:grid-cols-2">
        <div className="rounded-xl bg-slate-50 p-3 dark:bg-slate-700/50">
          <p className="text-xs text-slate-500 dark:text-slate-400">当前能力</p>
          <p className="mt-1 font-semibold text-slate-800 dark:text-white">{proposal.previousLevel ? abilityLevelDisplay(proposal.previousLevel).label : '暂无'} · {proposal.previousScore ?? '—'} 分</p>
        </div>
        <div className="rounded-xl bg-primary-50 p-3 dark:bg-primary-900/20">
          <p className="text-xs text-slate-500 dark:text-slate-400">建议能力</p>
          <p className="mt-1 font-semibold text-slate-800 dark:text-white">{abilityLevelDisplay(proposal.proposedLevel).label} · {proposal.proposedScore} 分</p>
        </div>
      </div>
      <p className="mt-3 text-sm text-slate-600 dark:text-slate-300">{proposal.proposedSummary}</p>
      {!status.actionable && proposal.finalLevel && (
        <p className="mt-2 text-sm text-slate-600 dark:text-slate-300">最终能力：{abilityLevelDisplay(proposal.finalLevel).label} · {proposal.finalScore ?? '—'} 分</p>
      )}

      <div className="mt-4 border-t border-slate-100 pt-4 dark:border-slate-700">
        <div className="flex items-center justify-between gap-2">
          <h5 className="text-sm font-medium text-slate-800 dark:text-white">本次证据 · {evidenceLoading ? '加载中' : evidence.length} 条</h5>
          {evidenceError && <button type="button" onClick={loadEvidence} className="inline-flex items-center gap-1 text-sm text-primary-600"><RefreshCw className="h-3.5 w-3.5" />重试</button>}
        </div>
        {evidenceError && <p role="alert" className="mt-2 text-sm text-rose-600">证据加载失败：{evidenceError}</p>}
        {!evidenceLoading && !evidenceError && evidence.length === 0 && <p className="mt-2 text-sm text-amber-700">缺少有效证据，暂不能确认。</p>}
        <ul className="mt-2 space-y-2">
          {evidence.map(item => (
            <li key={item.id} className="rounded-lg bg-slate-50 p-3 text-sm dark:bg-slate-700/40">
              <div className="flex flex-wrap justify-between gap-2 text-slate-700 dark:text-slate-200"><span>{item.topicSummary}</span><span>原始得分 {item.questionScore}</span></div>
              {item.feedbackSummary && <p className="mt-1 text-slate-500 dark:text-slate-400">{item.feedbackSummary}</p>}
              <Link to={ROUTES.interviewDetail(item.interviewSessionId)} className="mt-1 inline-flex items-center gap-1 text-primary-600 hover:underline">查看来源面试 <ExternalLink className="h-3 w-3" /></Link>
            </li>
          ))}
        </ul>
      </div>

      {status.actionable && isCurrentSession && (
        <div className="mt-4 space-y-3">
          {lowEvidence && !evidenceLoading && !evidenceError && evidence.length > 0 && (
            <label className="flex items-start gap-2 rounded-lg bg-amber-50 p-3 text-sm text-amber-800">
              <input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} className="mt-1" />
              本次仅有 {evidence.length} 条证据，确认已查看证据后再接受。
            </label>
          )}
          {editing && (
            <div className="space-y-2">
              <label className="block text-sm text-slate-700 dark:text-slate-200">确认等级
                <select value={level} onChange={event => setLevel(event.target.value as AbilityLevel)} className="mt-1 w-full rounded-lg border border-slate-300 bg-white p-2 dark:border-slate-600 dark:bg-slate-700">
                  <option value="">请选择</option>
                  {levels.map(item => <option key={item} value={item}>{abilityLevelDisplay(item).label}</option>)}
                </select>
              </label>
              <label className="block text-sm text-slate-700 dark:text-slate-200">确认摘要
                <textarea value={summary} maxLength={2000} onChange={event => setSummary(event.target.value)} rows={3} className="mt-1 w-full rounded-lg border border-slate-300 bg-white p-2 dark:border-slate-600 dark:bg-slate-700" />
              </label>
              <p className="text-xs text-slate-500">仅可修改等级与摘要；原始证据和得分保持只读。</p>
            </div>
          )}
          {error && <p role="alert" className="text-sm text-rose-600">{error}</p>}
          <div className="flex flex-wrap gap-2">
            <button type="button" disabled={!canAct || (lowEvidence && !confirmed)} onClick={() => decide(editing ? 'edit' : 'accept')} className="inline-flex items-center gap-1 rounded-lg bg-primary-600 px-3 py-2 text-sm text-white disabled:cursor-not-allowed disabled:opacity-50"><Check className="h-4 w-4" />{busy ? '处理中…' : editing ? '编辑后接受' : '接受建议'}</button>
            <button type="button" disabled={busy} onClick={() => setEditing(value => !value)} className="inline-flex items-center gap-1 rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-700 dark:text-slate-200"><Pencil className="h-4 w-4" />{editing ? '取消编辑' : '编辑后接受'}</button>
            <button type="button" disabled={!canAct} onClick={() => decide('reject')} className="inline-flex items-center gap-1 rounded-lg border border-rose-200 px-3 py-2 text-sm text-rose-700 disabled:opacity-50"><X className="h-4 w-4" />拒绝</button>
          </div>
        </div>
      )}
    </article>
  );
}
