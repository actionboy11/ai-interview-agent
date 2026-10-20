import request, { clearCsrfToken } from './request.ts';
import { isSameUserId } from '../utils/userId.ts';
import type {
  AdminCreateUserRequest, AdminUser, ChangePasswordRequest, CreateInviteRequest,
  CurrentUser, InviteRegistrationRequest, IssuedInvite, LoginRequest,
} from '../types/auth.ts';

export const authApi = {
  async login(body: LoginRequest): Promise<CurrentUser> {
    const user = await request.post<CurrentUser>('/api/auth/login', body);
    // Spring clears the old token on login; the next mutation bootstraps its replacement.
    clearCsrfToken();
    return user;
  },
  async logout(): Promise<void> {
    await request.post<void>('/api/auth/logout');
    clearCsrfToken();
  },
  me: () => request.get<CurrentUser>('/api/auth/me'),
  registerByInvite: (body: InviteRegistrationRequest) => request.post<CurrentUser>('/api/auth/register-by-invite', body),
  changePassword: (body: ChangePasswordRequest) => request.post<void>('/api/auth/change-password', body),
};

export const adminApi = {
  createUser: (body: AdminCreateUserRequest) => request.post<AdminUser>('/api/admin/users', body),
  async disableUser(id: string, currentUserId: string): Promise<void> {
    if (isSameUserId(id, currentUserId)) throw new Error('不能禁用当前登录账号');
    await request.post<void>(`/api/admin/users/${encodeURIComponent(id)}/disable`);
  },
  enableUser: (id: string) => request.post<void>(`/api/admin/users/${encodeURIComponent(id)}/enable`),
  issueInvite: (body: CreateInviteRequest) => request.post<IssuedInvite>('/api/admin/invites', body),
};
