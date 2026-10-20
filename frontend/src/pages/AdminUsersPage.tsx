import { useState, type FormEvent } from 'react';
import { Ban, CheckCircle2, KeyRound, Loader2, ShieldCheck, UserPlus, X } from 'lucide-react';
import { adminApi } from '../api/auth';
import { getErrorMessage } from '../api/request';
import { authButtonClass, authInputClass } from '../components/AuthPageShell';
import { useAuth } from '../hooks/useAuth';
import { isSameUserId } from '../utils/userId';
import type { AdminUser, IssuedInvite, UserRole, UserStatus } from '../types/auth';

export default function AdminUsersPage() {
  const { currentUser } = useAuth();
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<UserRole>('USER');
  const [targetId, setTargetId] = useState('');
  const [ttlHours, setTtlHours] = useState(24);
  const [invite, setInvite] = useState<IssuedInvite | null>(null);
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [pending, setPending] = useState('');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const busy = pending !== '';
  const cardClass = 'rounded-2xl border border-slate-100 bg-white p-6 shadow-sm dark:border-slate-700 dark:bg-slate-900';
  const labelClass = 'block text-sm font-medium text-slate-600 dark:text-slate-300';

  const createUser = async (event: FormEvent) => {
    event.preventDefault();
    setPending('create');
    setError('');
    setSuccess('');
    try {
      const user = await adminApi.createUser({ username, displayName, password, role });
      setUsers(previous => [user, ...previous]);
      setUsername('');
      setDisplayName('');
      setSuccess(`已创建 ${user.displayName}，首次登录需修改临时密码。`);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setPassword('');
      setPending('');
    }
  };

  const changeStatus = async (status: UserStatus) => {
    const id = targetId.trim();
    if (!id || !currentUser || (status === 'DISABLED' && isSameUserId(id, currentUser.id))) {
      setError('不能禁用当前登录账号');
      return;
    }
    setPending(status);
    setError('');
    setSuccess('');
    try {
      await (status === 'ACTIVE' ? adminApi.enableUser(id) : adminApi.disableUser(id, currentUser.id));
      setUsers(previous => previous.map(user => isSameUserId(user.id, id) ? { ...user, status } : user));
      setSuccess(status === 'ACTIVE' ? '账号已启用。' : '账号已禁用，现有会话已失效。');
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setPending('');
    }
  };

  const issueInvite = async (event: FormEvent) => {
    event.preventDefault();
    setPending('invite');
    setError('');
    setSuccess('');
    setInvite(null);
    try {
      setInvite(await adminApi.issueInvite({ ttlSeconds: ttlHours * 3600 }));
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setPending('');
    }
  };

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <div className="flex items-center gap-3"><ShieldCheck className="h-7 w-7 text-primary-500" /><h1 className="text-2xl font-bold text-slate-900 dark:text-white">用户与邀请管理</h1></div>
      {error && <p role="alert" className="rounded-xl bg-red-50 p-4 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{error}</p>}
      {success && <p role="status" className="rounded-xl bg-emerald-50 p-4 text-sm text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300">{success}</p>}
      <div className="grid gap-6 lg:grid-cols-2">
        <section className={cardClass}>
          <h2 className="mb-4 text-lg font-semibold text-slate-900 dark:text-white">创建用户</h2>
          <form onSubmit={createUser} className="space-y-4">
            <label className={labelClass}>用户名<input name="username" autoComplete="off" required maxLength={100} value={username} onChange={event => setUsername(event.target.value)} disabled={busy} className={authInputClass} /></label>
            <label className={labelClass}>显示名称<input name="displayName" required maxLength={100} value={displayName} onChange={event => setDisplayName(event.target.value)} disabled={busy} className={authInputClass} /></label>
            <label className={labelClass}>临时密码<input name="password" type="password" autoComplete="new-password" required value={password} onChange={event => setPassword(event.target.value)} disabled={busy} className={authInputClass} /></label>
            <label className={labelClass}>角色<select value={role} onChange={event => setRole(event.target.value as UserRole)} disabled={busy} className={authInputClass}><option value="USER">普通用户</option><option value="ADMIN">管理员</option></select></label>
            <button disabled={busy} className={authButtonClass}>{pending === 'create' ? <Loader2 className="h-4 w-4 animate-spin" /> : <UserPlus className="h-4 w-4" />}创建用户</button>
          </form>
        </section>
        <div className="space-y-6">
          <section className={cardClass}>
            <h2 className="mb-4 text-lg font-semibold text-slate-900 dark:text-white">签发邀请码</h2>
            <form onSubmit={issueInvite} className="space-y-4">
              <label className={labelClass}>有效时长（小时）<input name="ttlHours" type="number" required min={1} step={1} value={ttlHours} onChange={event => setTtlHours(Number(event.target.value))} disabled={busy || invite !== null} className={authInputClass} /></label>
              <button disabled={busy || invite !== null} className={authButtonClass}>{pending === 'invite' ? <Loader2 className="h-4 w-4 animate-spin" /> : <KeyRound className="h-4 w-4" />}签发邀请码</button>
            </form>
            {invite && <div role="status" className="mt-4 space-y-3 rounded-xl bg-primary-50 p-4 dark:bg-primary-900/30">
              <label className={labelClass}>邀请码（仅展示本次）<input readOnly value={invite.rawCode} autoComplete="off" onFocus={event => event.target.select()} className={`${authInputClass} font-mono`} /></label>
              <p className="text-xs text-slate-500">到期：{new Date(invite.expiresAt).toLocaleString()}。关闭或离开页面后无法再次查看。</p>
              <button onClick={() => setInvite(null)} className="flex items-center gap-1 text-sm font-medium text-primary-600"><X className="h-4 w-4" />已保存，关闭</button>
            </div>}
          </section>
          <section className={cardClass}>
            <h2 className="mb-4 text-lg font-semibold text-slate-900 dark:text-white">启用 / 禁用账号</h2>
            <form onSubmit={event => {
              event.preventDefault();
              const action = (event.nativeEvent as SubmitEvent).submitter?.getAttribute('value');
              void changeStatus(action === 'disable' ? 'DISABLED' : 'ACTIVE');
            }} className="space-y-4">
              <label className={labelClass}>用户 UUID<input name="userId" required pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" placeholder="输入用户 UUID" value={targetId} onChange={event => setTargetId(event.target.value)} disabled={busy} className={`${authInputClass} font-mono`} /></label>
              <div className="flex gap-3">
                <button type="submit" value="enable" disabled={busy || !targetId.trim()} className={authButtonClass}><CheckCircle2 className="h-4 w-4" />启用</button>
                <button type="submit" value="disable" disabled={busy || !targetId.trim() || isSameUserId(targetId, currentUser?.id)} className="flex w-full items-center justify-center gap-2 rounded-xl border border-red-200 px-4 py-3 font-medium text-red-600 disabled:opacity-50"><Ban className="h-4 w-4" />禁用</button>
              </div>
              {(pending === 'ACTIVE' || pending === 'DISABLED') && <p role="status" className="text-sm text-slate-500">正在更新账号…</p>}
            </form>
          </section>
        </div>
      </div>
      <section className={cardClass}>
        <h2 className="text-lg font-semibold text-slate-900 dark:text-white">本页新建用户</h2>
        <p className="mt-1 text-sm text-slate-500">仅保留当前页面内的创建记录，刷新或离开后清空。</p>
        {users.length === 0 ? <p className="py-6 text-sm text-slate-400">暂无新建记录</p> : <div className="mt-4 overflow-x-auto"><table className="w-full text-left text-sm text-slate-600 dark:text-slate-300"><thead><tr className="border-b border-slate-100 dark:border-slate-700"><th className="p-3">用户</th><th className="p-3">角色</th><th className="p-3">状态</th><th className="p-3">UUID</th></tr></thead><tbody>{users.map(user => <tr key={user.id} className="border-b border-slate-100 dark:border-slate-700"><td className="p-3">{user.displayName}<span className="block text-xs text-slate-400">{user.username}</span></td><td className="p-3">{user.role === 'ADMIN' ? '管理员' : '普通用户'}</td><td className="p-3">{user.status === 'ACTIVE' ? '已启用' : '已禁用'}</td><td className="p-3"><button onClick={() => setTargetId(user.id)} className="font-mono text-xs text-primary-600 hover:underline" title="填入账号操作">{user.id}</button></td></tr>)}</tbody></table></div>}
      </section>
    </div>
  );
}
