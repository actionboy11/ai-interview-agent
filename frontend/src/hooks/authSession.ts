import { authApi } from '../api/auth.ts';
import { getCsrfHeaders, subscribeAuthFailure } from '../api/request.ts';
import type { CurrentUser, LoginRequest } from '../types/auth.ts';
import { ROUTES } from '../constants/routes.ts';

/** Internal, memory-only session store shared with the React provider. */
export function createAuthSession(onExpired: (path: string) => void) {
  let snapshot: { currentUser: CurrentUser | null; loading: boolean } = { currentUser: null, loading: true };
  let revision = 0;
  let expired = false;
  const listeners = new Set<() => void>();
  const publish = (currentUser: CurrentUser | null, loading = false) => {
    snapshot = { currentUser, loading };
    listeners.forEach(listener => listener());
  };

  return {
    getSnapshot: () => snapshot,
    subscribe(listener: () => void) {
      listeners.add(listener);
      return () => { listeners.delete(listener); };
    },
    connect: () => subscribeAuthFailure(() => {
      revision += 1;
      publish(null);
      if (!expired) {
        expired = true;
        onExpired(ROUTES.login);
      }
    }),
    async login(body: LoginRequest): Promise<void> {
      const currentRevision = ++revision;
      const user = await authApi.login(body);
      if (currentRevision === revision) {
        expired = false;
        publish(user);
      }
    },
    async logout(): Promise<void> {
      revision += 1;
      await authApi.logout();
      publish(null);
    },
    async refreshCurrentUser(): Promise<void> {
      const currentRevision = ++revision;
      publish(snapshot.currentUser, true);
      try {
        await getCsrfHeaders();
        const user = await authApi.me();
        if (currentRevision === revision) {
          expired = false;
          publish(user);
        }
      } catch (error) {
        if (currentRevision === revision) publish(null);
        throw error;
      }
    },
  };
}
