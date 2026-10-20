import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { Loader2, UserPlus } from 'lucide-react';
import AuthPageShell, { authButtonClass, authInputClass } from '../components/AuthPageShell';
import { useAuth } from '../hooks/useAuth';
import { authApi } from '../api/auth';
import { getErrorMessage } from '../api/request';
import { ROUTES } from '../constants/routes';

export default function InviteRegisterPage() {
  const { currentUser, loading } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [inviteCode, setInviteCode] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');

  if (loading) return <AuthPageShell title="正在验证会话" error=""><p role="status" className="text-slate-500">正在加载…</p></AuthPageShell>;
  if (currentUser) return <Navigate to={currentUser.mustChangePassword ? ROUTES.login : ROUTES.home} replace />;

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    setPending(true);
    try {
      await authApi.registerByInvite({ username, displayName, password, inviteCode });
      navigate(ROUTES.login, { replace: true, state: { registered: true } });
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setPassword('');
      setInviteCode('');
      setPending(false);
    }
  };

  return (
    <AuthPageShell title="邀请注册" error={error}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <label className="block text-sm font-medium text-slate-600 dark:text-slate-300">邀请码<input name="inviteCode" autoComplete="off" required value={inviteCode} onChange={event => setInviteCode(event.target.value)} disabled={pending} className={authInputClass} /></label>
        <label className="block text-sm font-medium text-slate-600 dark:text-slate-300">用户名<input name="username" autoComplete="username" required maxLength={100} value={username} onChange={event => setUsername(event.target.value)} disabled={pending} className={authInputClass} /></label>
        <label className="block text-sm font-medium text-slate-600 dark:text-slate-300">显示名称<input name="displayName" autoComplete="nickname" required maxLength={100} value={displayName} onChange={event => setDisplayName(event.target.value)} disabled={pending} className={authInputClass} /></label>
        <label className="block text-sm font-medium text-slate-600 dark:text-slate-300">密码<input name="password" type="password" autoComplete="new-password" required value={password} onChange={event => setPassword(event.target.value)} disabled={pending} className={authInputClass} /></label>
        <button type="submit" disabled={pending} className={authButtonClass}>{pending ? <Loader2 className="h-4 w-4 animate-spin" /> : <UserPlus className="h-4 w-4" />}{pending ? '注册中…' : '注册'}</button>
      </form>
      <p className="mt-6 text-center text-sm text-slate-500">已有账号？<Link to={ROUTES.login} className="ml-1 font-medium text-primary-600 hover:underline">返回登录</Link></p>
    </AuthPageShell>
  );
}
