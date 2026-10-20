export type UserRole = 'USER' | 'ADMIN';
export type UserStatus = 'ACTIVE' | 'DISABLED';

export interface CurrentUser {
  id: string;
  username: string;
  displayName: string;
  role: UserRole;
  mustChangePassword: boolean;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface InviteRegistrationRequest extends LoginRequest {
  displayName: string;
  inviteCode: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export interface AdminCreateUserRequest extends LoginRequest {
  displayName: string;
  role: UserRole;
}

export interface AdminUser extends CurrentUser {
  status: UserStatus;
  createdAt: string;
  updatedAt: string;
  lastLoginAt: string | null;
}

export interface CreateInviteRequest {
  ttlSeconds: number;
}

export interface IssuedInvite {
  id: string;
  rawCode: string;
  expiresAt: string;
}
