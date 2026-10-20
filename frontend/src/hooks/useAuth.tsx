import { createContext, useContext, useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { createAuthSession } from './authSession';
import { ROUTES } from '../constants/routes';
import type { CurrentUser, LoginRequest } from '../types/auth';

interface AuthContextValue {
  currentUser: CurrentUser | null;
  loading: boolean;
  login: (body: LoginRequest) => Promise<void>;
  logout: () => Promise<void>;
  refreshCurrentUser: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const location = useLocation();
  const path = useRef(location.pathname);
  path.current = location.pathname;
  const [session] = useState(() => createAuthSession(() => {
    if (path.current !== ROUTES.login && path.current !== ROUTES.inviteRegister) {
      navigate(ROUTES.login, { replace: true });
    }
  }));
  const snapshot = useSyncExternalStore(session.subscribe, session.getSnapshot);

  useEffect(() => {
    const disconnect = session.connect();
    void session.refreshCurrentUser().catch(() => {
      // 登录页提供重试入口；过期会话已由 API 统一清理。
    });
    return disconnect;
  }, [session]);

  return (
    <AuthContext.Provider value={{ ...snapshot, login: session.login, logout: session.logout, refreshCurrentUser: session.refreshCurrentUser }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth 必须在 AuthProvider 内使用');
  return context;
}
