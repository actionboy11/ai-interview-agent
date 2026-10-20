import test from 'node:test';
import assert from 'node:assert/strict';
import { AxiosHeaders, type AxiosAdapter } from 'axios';
import request, { clearCsrfToken, getCsrfHeaders, subscribeAuthFailure } from './request.ts';

const responseAdapter = (code: number, data: unknown = null): AxiosAdapter => async config => ({
  data: { code, message: code === 200 ? 'success' : '会话已过期', data },
  status: 200, statusText: 'OK', headers: {}, config,
});

test('所有请求携带会话 cookie，普通 Result 继续解包', async () => {
  const instance = request.getInstance();
  instance.defaults.adapter = async config => {
    assert.equal(config.withCredentials, true);
    return responseAdapter(200, { title: '简历' })(config);
  };
  assert.deepEqual(await request.get('/api/resumes'), { title: '简历' });
});

test('并行写请求先共享 CSRF 初始化并发送服务端指定的 header', async () => {
  const calls: string[] = [];
  request.getInstance().defaults.adapter = async config => {
    calls.push(config.url!);
    if (config.url === '/api/auth/csrf') {
      return responseAdapter(200, { headerName: 'X-XSRF-TOKEN', token: 'csrf-first' })(config);
    }
    assert.equal(AxiosHeaders.from(config.headers).get('X-XSRF-TOKEN'), 'csrf-first');
    return responseAdapter(200, { saved: true })(config);
  };
  await Promise.all([request.post('/api/item'), request.delete('/api/item/1')]);
  assert.deepEqual(calls, ['/api/auth/csrf', '/api/item', '/api/item/1']);
});

test('会话错误通知清理订阅者，权限错误保留登录态', async () => {
  let expirations = 0;
  const unsubscribe = subscribeAuthFailure(() => { expirations += 1; });
  try {
    request.getInstance().defaults.adapter = responseAdapter(403);
    await assert.rejects(request.get('/api/admin/users'), /会话已过期/);
    assert.equal(expirations, 0);
    for (const code of [401, 12002]) {
      request.getInstance().defaults.adapter = responseAdapter(code);
      await assert.rejects(request.get('/api/auth/me'), /会话已过期/);
    }
    assert.equal(expirations, 2);
  } finally {
    unsubscribe();
  }
});

test('下载返回的 JSON 会话错误仍会触发失效处理', async () => {
  let expired = false;
  const unsubscribe = subscribeAuthFailure(() => { expired = true; });
  request.getInstance().defaults.adapter = async config => ({
    data: new Blob([JSON.stringify({ code: 401, message: '请登录', data: null })], { type: 'application/json' }),
    status: 200, statusText: 'OK', headers: {}, config,
  });
  try {
    await assert.rejects(request.download('/api/export'), /请登录/);
    assert.equal(expired, true);
  } finally {
    unsubscribe();
  }
});

test('迟到的旧 CSRF 初始化不能覆盖登录后轮换的 token', async () => {
  clearCsrfToken();
  let resolveOld: (() => void) | undefined;
  let calls = 0;
  request.getInstance().defaults.adapter = async config => {
    const token = ++calls === 1 ? 'old-token' : 'new-token';
    if (token === 'old-token') await new Promise<void>(resolve => { resolveOld = resolve; });
    return responseAdapter(200, { headerName: 'X-XSRF-TOKEN', token })(config);
  };
  const oldHeaders = getCsrfHeaders();
  await new Promise(resolve => setImmediate(resolve));
  clearCsrfToken();
  assert.deepEqual(await getCsrfHeaders(), { 'X-XSRF-TOKEN': 'new-token' });
  resolveOld?.();
  await oldHeaders;
  assert.deepEqual(await getCsrfHeaders(), { 'X-XSRF-TOKEN': 'new-token' });
});

test('CSRF 初始化失败时不提交写请求并保留服务端错误', async () => {
  clearCsrfToken();
  const calls: string[] = [];
  request.getInstance().defaults.adapter = async config => {
    calls.push(config.url!);
    return { data: { code: 500, message: '安全令牌暂不可用', data: null }, status: 200, statusText: 'OK', headers: {}, config };
  };
  await assert.rejects(request.post('/api/admin/users'), /安全令牌暂不可用/);
  assert.deepEqual(calls, ['/api/auth/csrf']);
});
