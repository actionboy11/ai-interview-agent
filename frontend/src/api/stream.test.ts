import test from 'node:test';
import assert from 'node:assert/strict';
import request from './request.ts';
import { streamSse } from './stream.ts';

test('SSE 写请求复用 CSRF 初始化，携带 cookie 并保留原请求头', async () => {
  request.getInstance().defaults.adapter = async config => ({
    data: { code: 200, message: 'success', data: { headerName: 'X-XSRF-TOKEN', token: 'stream-token' } },
    status: 200, statusText: 'OK', headers: {}, config,
  });
  const originalFetch = globalThis.fetch;
  let init: RequestInit | undefined;
  globalThis.fetch = async (_url, requestInit) => {
    init = requestInit;
    return new Response('data: answer\n', { headers: { 'Content-Type': 'text/event-stream' } });
  };
  const chunks: string[] = [];
  let completed = false;
  try {
    await streamSse({
      url: '/api/chat', init: { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' },
      onMessage: chunk => chunks.push(chunk), onComplete: () => { completed = true; }, onError: error => { throw error; },
    });
    assert.equal(init?.credentials, 'include');
    assert.equal(new Headers(init?.headers).get('X-XSRF-TOKEN'), 'stream-token');
    assert.equal(new Headers(init?.headers).get('Content-Type'), 'application/json');
    assert.deepEqual(chunks, ['answer']);
    assert.equal(completed, true);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
