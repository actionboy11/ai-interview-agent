import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveVoiceWebSocketUrl } from './webSocketUrl.ts';

const page = (protocol: string, host: string, hostname: string) => ({
  protocol,
  host,
  hostname,
});

test('HTTPS 页面使用当前域名生成 wss 地址', () => {
  assert.equal(
    resolveVoiceWebSocketUrl(7, '/ws/voice-interview/7', page('https:', 'example.com', 'example.com')),
    'wss://example.com/ws/voice-interview/7',
  );
});

test('HTTP 页面保留当前端口并生成 ws 地址', () => {
  assert.equal(
    resolveVoiceWebSocketUrl(7, undefined, page('http:', 'example.com:8088', 'example.com')),
    'ws://example.com:8088/ws/voice-interview/7',
  );
});

test('公网 HTTPS 页面忽略后端遗留的 localhost 地址', () => {
  assert.equal(
    resolveVoiceWebSocketUrl(
      7,
      'ws://localhost:8080/ws/voice-interview/7',
      page('https:', 'demo.example.com', 'demo.example.com'),
    ),
    'wss://demo.example.com/ws/voice-interview/7',
  );
});

test('本地开发页面可以使用显式 localhost 地址', () => {
  assert.equal(
    resolveVoiceWebSocketUrl(
      7,
      'ws://localhost:8080/ws/voice-interview/7',
      page('http:', 'localhost:5173', 'localhost'),
    ),
    'ws://localhost:8080/ws/voice-interview/7',
  );
});

test('忽略候选地址中的意外路径', () => {
  assert.equal(
    resolveVoiceWebSocketUrl(
      7,
      'ws://localhost:8080/ws/unexpected/99',
      page('http:', 'localhost:5173', 'localhost'),
    ),
    'ws://localhost:5173/ws/voice-interview/7',
  );
});
