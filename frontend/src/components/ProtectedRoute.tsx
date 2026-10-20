import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { useAuth } from '../hooks/useAuth';
import { ROUTES } from '../constants/routes';
import type { UserRole } from '../types/auth';

export default function ProtectedRoute({ role }: { role?: UserRole }) {
  const { currentUser, loading } = useAuth();
  const location = useLocation();
  if (loading) return <div role="status" className="flex min-h-screen items-center justify-center text-slate-500"><Loader2 className="mr-2 h-5 w-5 animate-spin" />正在验证会话…</div>;
  if (!currentUser || currentUser.mustChangePassword) {
    return <Navigate to={ROUTES.login} replace state={{ from: location.pathname + location.search }} />;
  }
  if (role && currentUser.role !== role) return <Navigate to={ROUTES.home} replace />;
  return <Outlet />;
}
