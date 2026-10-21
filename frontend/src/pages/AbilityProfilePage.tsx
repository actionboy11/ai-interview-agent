import { useEffect, useMemo, useState, useSyncExternalStore } from 'react';
import { ExternalLink, RefreshCw, ShieldOff, ShieldCheck } from 'lucide-react';
import { Link } from 'react-router-dom';
import { memoryApi } from '../api/memory';
import { getErrorMessage } from '../api/request';
import { ROUTES } from '../constants/routes';
import type { AbilityEvidence, AbilityProfile } from '../types/memory';
import { abilityLevelDisplay, profileStatusDisplay, proposalStatusDisplay } from '../utils/abilityLevel';
import { LatestCollection } from '../utils/latestCollection';

export default function AbilityProfilePage() {
  const collection = useMemo(() => new LatestCollection<AbilityProfile>(() => memoryApi.profiles()), []);
  const { items: profiles, loading, error: listError } = useSyncExternalStore(collection.subscribe, collection.getSnapshot);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [evidence, setEvidence] = useState<AbilityEvidence[]>([]);
  const [evidenceLoading, setEvidenceLoading] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [evidenceError, setEvidenceError] = useState('');

  useEffect(() => {
    void collection.load();
    return () => collection.invalidate();
  }, [collection]);

  useEffect(() => {
    if (selectedId === null) return;
    let active = true;
    setEvidence([]);
    setEvidenceLoading(true);
    setEvidenceError('');
    memoryApi.profileEvidence(selectedId)
      .then(items => { if (active) setEvidence(items); })
      .catch(err => { if (active) setEvidenceError(getErrorMessage(err)); })
      .finally(() => { if (active) setEvidenceLoading(false); });
    return () => { active = false; };
  }, [selectedId]);

  const changeStatus = async (profile: AbilityProfile) => {
    const action = profileStatusDisplay(profile.status).action;
    if (!action || busyId !== null) return;
    setBusyId(profile.id);
    setError('');
    setNotice('');
    try {
      const updated = action === 'disable'
        ? await memoryApi.disable(profile.id, profile.version)
        : await memoryApi.enable(profile.id, profile.version);
      collection.update(items => items.map(item => item.id === updated.id ? updated : item));
      setNotice(action === 'disable' ? '已停用能力画像' : '已启用能力画像');
    } catch (err) {
      const message = getErrorMessage(err);
      await collection.load();
      setError(`${message}。${collection.getSnapshot().error ? '刷新失败，请稍后重试。' : '画像已刷新，请重试。'}`);
    } finally {
      setBusyId(null);
    }
  };

  const selected = profiles.find(profile => profile.id === selectedId);

  return (
    <div className="mx-auto max-w-6xl space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">我的能力画像</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">来自你已确认的面试证据</p>
        </div>
        <button type="button" onClick={() => void collection.load()} disabled={loading} className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 disabled:opacity-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"><RefreshCw className="h-4 w-4" />刷新</button>
      </div>
      {(error || listError) && <p role="alert" className="rounded-lg bg-rose-50 p-3 text-sm text-rose-700">{error || listError}</p>}
      {notice && <p role="status" className="rounded-lg bg-emerald-50 p-3 text-sm text-emerald-700">{notice}</p>}
      {loading && <p className="text-sm text-slate-500">正在加载能力画像…</p>}
      {!loading && !error && !listError && profiles.length === 0 && <p className="rounded-xl bg-white p-6 text-slate-500 dark:bg-slate-800">暂无已确认能力。完成面试并在报告中确认建议后会显示在这里。</p>}

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <div className="space-y-3">
          {profiles.map(profile => {
            const status = profileStatusDisplay(profile.status);
            const level = abilityLevelDisplay(profile.level);
            return (
              <article key={profile.id} className={`rounded-2xl border bg-white p-5 shadow-sm dark:bg-slate-800 ${selectedId === profile.id ? 'border-primary-400' : 'border-slate-200 dark:border-slate-700'}`}>
                <div className="flex flex-wrap justify-between gap-2">
                  <div>
                    <h2 className="font-semibold text-slate-900 dark:text-white">{profile.skillId} · {profile.categoryKey}</h2>
                    <p className="mt-1 text-sm text-slate-500">已确认 {profile.confirmedEvidenceCount} 条证据 · 置信度 {Math.round(profile.confidence * 100)}%</p>
                  </div>
                  <span className={`h-fit rounded-full px-3 py-1 text-xs ${status.className}`}>{status.label}</span>
                </div>
                <div className="mt-3 flex items-center gap-2"><span className={`rounded-full px-3 py-1 text-sm font-medium ${level.className}`}>{level.label}</span><span className="font-semibold text-slate-800 dark:text-white">{profile.score} 分</span>{profile.manuallyEdited && <span className="text-xs text-slate-500">曾人工调整等级或摘要</span>}</div>
                <p className="mt-3 text-sm text-slate-600 dark:text-slate-300">{profile.summary}</p>
                <div className="mt-4 flex flex-wrap gap-2">
                  <button type="button" onClick={() => setSelectedId(profile.id)} className="rounded-lg bg-primary-50 px-3 py-2 text-sm text-primary-700 hover:bg-primary-100">查看证据</button>
                  {status.action && <button type="button" disabled={busyId !== null || (status.action === 'enable' && profile.confirmedEvidenceCount === 0)} onClick={() => void changeStatus(profile)} className="inline-flex items-center gap-1 rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-700 disabled:opacity-50 dark:border-slate-600 dark:text-slate-200">{status.action === 'disable' ? <ShieldOff className="h-4 w-4" /> : <ShieldCheck className="h-4 w-4" />}{busyId === profile.id ? '处理中…' : status.action === 'disable' ? '停用' : '启用'}</button>}
                </div>
              </article>
            );
          })}
        </div>

        {selected && (
          <aside className="h-fit rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
            <h2 className="font-semibold text-slate-900 dark:text-white">{selected.skillId} · {selected.categoryKey} 的相关证据</h2>
            <p className="mt-1 text-xs text-slate-500">仅“已接受”和“编辑后接受”的证据计入画像；待确认或已拒绝的记录仅供追溯。原始评分不可编辑。</p>
            {evidenceLoading && <p className="mt-4 text-sm text-slate-500">加载证据中…</p>}
            {evidenceError && <p role="alert" className="mt-4 text-sm text-rose-600">{evidenceError}</p>}
            {!evidenceLoading && !evidenceError && evidence.length === 0 && <p className="mt-4 text-sm text-slate-500">暂无证据记录。</p>}
            <ul className="mt-4 space-y-3">
              {evidence.map(item => (
                <li key={item.id} className="rounded-xl bg-slate-50 p-3 text-sm dark:bg-slate-700/50">
                  <div className="flex flex-wrap justify-between gap-2"><strong className="text-slate-800 dark:text-white">{item.topicSummary}</strong><span className="text-slate-600 dark:text-slate-300">原始得分 {item.questionScore}</span></div>
                  <p className="mt-1 text-xs text-slate-500">{item.difficulty} · {proposalStatusDisplay(item.proposalStatus).label} · {new Date(item.occurredAt).toLocaleDateString('zh-CN')}</p>
                  {item.feedbackSummary && <p className="mt-2 text-slate-600 dark:text-slate-300">{item.feedbackSummary}</p>}
                  <Link to={ROUTES.interviewDetail(item.interviewSessionId)} className="mt-2 inline-flex items-center gap-1 text-primary-600 hover:underline">查看来源面试 <ExternalLink className="h-3.5 w-3.5" /></Link>
                </li>
              ))}
            </ul>
          </aside>
        )}
      </div>
    </div>
  );
}
