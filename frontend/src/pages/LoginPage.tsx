import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { Loader2, LogIn, KeyRound } from 'lucide-react';
import AuthPageShell, { authButtonClass, authInputClass } from '../components/AuthPageShell';
import { useAuth } from '../hooks/useAuth';
import { authApi } from '../api/auth';
import { getErrorMessage } from '../api/request';
import { ROUTES } from '../constants/routes';

export default function LoginPage() {
  const { currentUser, loading, login, refreshCurrentUser } = useAuth();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const mustChangePassword = currentUser?.mustChangePassword ?? false;
  const from = (location.state as { from?: unknown } | null)?.from;
  const destination = typeof from === 'string' && from.startsWith('/') && !from.startsWith('//')
    && ![ROUTES.login, ROUTES.inviteRegister].some(route => from.split('?')[0] === route) ? from : ROUTES.home;

  if (loading) return <AuthPageShell title="正在验证会话" error=""><p role="status" className="text-slate-500">正在加载…</p></AuthPageShell>;
  if (currentUser && !mustChangePassword) return <Navigate to={destination} replace />;

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setPending(true);
    setError('');
    try {
      if (mustChangePassword) {
        await authApi.changePassword({ currentPassword: password, newPassword });
        await refreshCurrentUser();
      } else {
        await login({ username, password });
      }
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setPassword('');
      setNewPassword('');
      setPending(false);
    }
  };

  return (
    <AuthPageShell title={mustChangePassword ? '设置新密码' : '登录账号'} error={error}>
      {location.state?.registered && !mustChangePassword && <p role="status" className="mb-4 text-sm text-emerald-600">注册成功，请登录。</p>}
      {mustChangePassword && <p className="mb-4 text-sm text-slate-500">首次登录请修改临时密码。</p>}
      <form onSubmit={handleSubmit} className="space-y-4">
        {!mustChangePassword && <label className="block text-sm font-medium text-slate-600 dark:text-slate-300">用户名<input name="username" autoComplete="username" required maxLength={100} value={username} onChange={event => setUsername(event.target.value)} disabled={pending} className={authInputClass} /></label>}
        <label className="block text-sm font-medium text-slate-600 dark:text-slate-300">{mustChangePassword ? '当前密码' : '密码'}<input name="password" type="password" autoComplete="current-password" required value={password} onChange={event => setPassword(event.target.value)} disabled={pending} className={authInputClass} /></label>
        {mustChangePassword && <label className="block text-sm font-medium text-slate-600 dark:text-slate-300">新密码<input name="newPassword" type="password" autoComplete="new-password" required value={newPassword} onChange={event => setNewPassword(event.target.value)} disabled={pending} className={authInputClass} /></label>}
        <button type="submit" disabled={pending} className={authButtonClass}>{pending ? <Loader2 className="h-4 w-4 animate-spin" /> : mustChangePassword ? <KeyRound className="h-4 w-4" /> : <LogIn className="h-4 w-4" />}{pending ? '处理中…' : mustChangePassword ? '更新密码' : '登录'}</button>
      </form>
      {!mustChangePassword && <p className="mt-6 text-center text-sm text-slate-500">已有邀请码？<Link to={ROUTES.inviteRegister} className="ml-1 font-medium text-primary-600 hover:underline">注册账号</Link></p>}
    </AuthPageShell>
  );
}
