import test, { beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { AxiosHeaders } from 'axios';
import request, { clearCsrfToken } from './request.ts';
import { authApi, adminApi } from './auth.ts';
import { createAuthSession } from '../hooks/authSession.ts';

const user = { id: 'user-id', username: 'alice', displayName: 'Alice', role: 'USER' as const, mustChangePassword: false };

beforeEach(() => clearCsrfToken());

test('登录及退出后更新 CSRF，邀请注册不会伪装成已登录', async () => {
  const calls: { url: string; data: unknown; csrf: unknown }[] = [];
  let token = 0;
  request.getInstance().defaults.adapter = async config => {
    calls.push({ url: config.url!, data: config.data ? JSON.parse(config.data) : null, csrf: AxiosHeaders.from(config.headers).get('X-XSRF-TOKEN') });
    const data = config.url === '/api/auth/csrf'
      ? { headerName: 'X-XSRF-TOKEN', token: `token-${++token}` }
      : user;
    return { data: { code: 200, message: 'success', data }, status: 200, statusText: 'OK', headers: {}, config };
  };
  assert.deepEqual(await authApi.login({ username: 'alice', password: 'safe-password' }), user);
  await authApi.logout();
  await authApi.registerByInvite({ username: 'alice', password: 'safe-password', displayName: 'Alice', inviteCode: 'invite-once' });
  assert.deepEqual(calls.map(call => call.url), [
    '/api/auth/csrf', '/api/auth/login', '/api/auth/csrf', '/api/auth/logout', '/api/auth/csrf', '/api/auth/register-by-invite',
  ]);
  assert.equal(calls[1].csrf, 'token-1');
  assert.deepEqual(calls[1].data, { username: 'alice', password: 'safe-password' });
  assert.equal(calls[3].csrf, 'token-2');
  assert.equal(calls[5].csrf, 'token-3');
  assert.deepEqual(calls[5].data, { username: 'alice', password: 'safe-password', displayName: 'Alice', inviteCode: 'invite-once' });
});

test('管理操作遵守现有后端路径和 DTO', async () => {
  const calls: { url: string; data: unknown }[] = [];
  request.getInstance().defaults.adapter = async config => {
    if (config.url === '/api/auth/csrf') {
      return { data: { code: 200, message: 'success', data: { headerName: 'X-XSRF-TOKEN', token: 'admin-token' } }, status: 200, statusText: 'OK', headers: {}, config };
    }
    calls.push({ url: config.url!, data: config.data ? JSON.parse(config.data) : null });
    return { data: { code: 200, message: 'success', data: {} }, status: 200, statusText: 'OK', headers: {}, config };
  };
  await adminApi.createUser({ username: 'bob', password: 'temporary-password', displayName: 'Bob', role: 'USER' });
  await adminApi.disableUser('target-id', 'current-admin-id');
  await adminApi.enableUser('target-id');
  await adminApi.issueInvite({ ttlSeconds: 3600 });
  assert.deepEqual(calls.map(call => call.url), ['/api/admin/users', '/api/admin/users/target-id/disable', '/api/admin/users/target-id/enable', '/api/admin/invites']);
  assert.deepEqual(calls[3].data, { ttlSeconds: 3600 });
});

test('管理员账号搜索与邀请码撤销使用持久化查询接口', async () => {
  const calls: { url: string; params: unknown; data: unknown }[] = [];
  request.getInstance().defaults.adapter = async config => {
    if (config.url === '/api/auth/csrf') {
      return { data: { code: 200, message: 'success', data: { headerName: 'X-XSRF-TOKEN', token: 'admin-token' } }, status: 200, statusText: 'OK', headers: {}, config };
    }
    calls.push({ url: config.url!, params: config.params, data: config.data });
    return { data: { code: 200, message: 'success', data: { content: [], totalElements: 0, totalPages: 0 } }, status: 200, statusText: 'OK', headers: {}, config };
  };
  await adminApi.listUsers({ search: 'invited', page: 1, size: 20 });
  await adminApi.listInvites({ page: 0, size: 20 });
  await adminApi.revokeInvite('invite-id');
  assert.deepEqual(calls.map(call => call.url), [
    '/api/admin/users', '/api/admin/invites', '/api/admin/invites/invite-id/revoke',
  ]);
  assert.deepEqual(calls[0].params, { search: 'invited', page: 1, size: 20 });
  assert.deepEqual(calls[1].params, { page: 0, size: 20 });
});

test('当前管理员 UUID 的大小写变体不能发出禁用请求', async () => {
  const calls: string[] = [];
  request.getInstance().defaults.adapter = async config => {
    calls.push(config.url!);
    const data = config.url === '/api/auth/csrf' ? { headerName: 'X-XSRF-TOKEN', token: 'admin-token' } : null;
    return { data: { code: 200, message: 'success', data }, status: 200, statusText: 'OK', headers: {}, config };
  };
  const currentAdminId = 'abcdef12-3456-789a-bcde-f0123456789a';
  for (const targetId of ['ABCDEF12-3456-789A-BCDE-F0123456789A', currentAdminId, '  AbCdEf12-3456-789A-bCdE-f0123456789A  ']) {
    await assert.rejects(adminApi.disableUser(targetId, currentAdminId), /不能禁用当前登录账号/);
  }
  assert.deepEqual(calls, []);
});

test('登录已成功时，下一次 CSRF 获取失败不能回滚内存中的登录状态', async () => {
  let csrfRequests = 0;
  request.getInstance().defaults.adapter = async config => {
    const data = config.url === '/api/auth/csrf'
      ? { headerName: 'X-XSRF-TOKEN', token: 'first-token' } : user;
    const code = config.url === '/api/auth/csrf' && ++csrfRequests > 1 ? 500 : 200;
    return { data: { code, message: code === 200 ? 'success' : '安全令牌暂不可用', data }, status: 200, statusText: 'OK', headers: {}, config };
  };
  const session = createAuthSession(() => {});
  await session.login({ username: 'alice', password: 'safe-password' });
  assert.equal(session.getSnapshot().currentUser?.username, 'alice');
  await assert.rejects(request.post('/api/private'), /安全令牌暂不可用/);
  assert.equal(session.getSnapshot().currentUser?.username, 'alice');
});

test('退出已成功时，即使后续 CSRF 获取失败也清空内存会话', async () => {
  let csrfRequests = 0;
  let loggedOut = false;
  request.getInstance().defaults.adapter = async config => {
    if (config.url === '/api/auth/logout') loggedOut = true;
    const data = config.url === '/api/auth/csrf'
      ? { headerName: 'X-XSRF-TOKEN', token: `token-${++csrfRequests}` } : user;
    const code = config.url === '/api/auth/csrf' && loggedOut ? 500 : 200;
    return { data: { code, message: code === 200 ? 'success' : '安全令牌暂不可用', data }, status: 200, statusText: 'OK', headers: {}, config };
  };
  const session = createAuthSession(() => {});
  await session.login({ username: 'alice', password: 'safe-password' });
  await session.logout();
  assert.equal(session.getSnapshot().currentUser, null);
  await assert.rejects(request.post('/api/private'), /安全令牌暂不可用/);
});

test('会话失效清空真实内存状态，只导航一次并丢弃迟到的 me 结果', async () => {
  const redirects: string[] = [];
  const session = createAuthSession(path => redirects.push(path));
  const disconnect = session.connect();
  let resolveMe: ((value: unknown) => void) | undefined;
  request.getInstance().defaults.adapter = async config => {
    if (config.url === '/api/auth/csrf') {
      return { data: { code: 200, message: 'success', data: { headerName: 'X-XSRF-TOKEN', token: 'session-token' } }, status: 200, statusText: 'OK', headers: {}, config };
    }
    if (config.url === '/api/auth/me' && resolveMe === undefined) {
      await new Promise(resolve => { resolveMe = resolve; });
    }
    const code = config.url === '/api/private' ? 401 : 200;
    return { data: { code, message: '请登录', data: user }, status: 200, statusText: 'OK', headers: {}, config };
  };
  try {
    await session.login({ username: 'alice', password: 'safe-password' });
    assert.equal(session.getSnapshot().currentUser?.username, 'alice');
    const refreshing = session.refreshCurrentUser();
    await new Promise(resolve => setImmediate(resolve));
    await assert.rejects(request.get('/api/private'), /请登录/);
    await assert.rejects(request.get('/api/private'), /请登录/);
    assert.equal(session.getSnapshot().currentUser, null);
    assert.deepEqual(redirects, ['/login']);
    resolveMe?.(null);
    await refreshing;
    assert.equal(session.getSnapshot().currentUser, null);
  } finally {
    disconnect();
  }
});
