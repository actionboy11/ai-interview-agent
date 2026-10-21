import test from 'node:test';
import assert from 'node:assert/strict';
import { LatestCollection } from './latestCollection.ts';

type Item = { id: number; status: string; version: number };
const proposed: Item = { id: 1, status: 'PROPOSED', version: 0 };
const accepted: Item = { id: 1, status: 'ACCEPTED', version: 1 };
const active: Item = { id: 1, status: 'ACTIVE', version: 0 };
const disabled: Item = { id: 1, status: 'DISABLED', version: 1 };

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

test('接受候选后，之前发起的轮询 GET 不能恢复待确认状态', async () => {
  const oldGet = deferred<Item[]>();
  const nextGet = deferred<Item[]>();
  let calls = 0;
  const resource = new LatestCollection<Item>(() => ++calls === 1 ? oldGet.promise : nextGet.promise, [proposed]);

  const polling = resource.load();
  resource.update(items => items.map(item => item.id === 1 ? accepted : item));
  const refreshed = resource.load();
  nextGet.resolve([accepted]);
  await refreshed;
  oldGet.resolve([proposed]);
  await polling;

  assert.deepEqual(resource.getSnapshot().items, [accepted]);
});

test('画像停用成功后，之前发起的列表 GET 不能回滚状态与版本', async () => {
  const oldGet = deferred<Item[]>();
  const resource = new LatestCollection<Item>(() => oldGet.promise, [active]);

  const refreshing = resource.load();
  resource.update(items => items.map(item => item.id === 1 ? disabled : item));
  oldGet.resolve([active]);
  await refreshing;

  assert.deepEqual(resource.getSnapshot().items, [disabled]);
  assert.equal(resource.getSnapshot().loading, false);
});

test('离开会话后旧请求不会发布结果', async () => {
  const oldGet = deferred<Item[]>();
  const resource = new LatestCollection<Item>(() => oldGet.promise, []);
  let notifications = 0;
  resource.subscribe(() => { notifications += 1; });
  const pending = resource.load();
  const beforeLeaving = notifications;
  resource.invalidate();
  oldGet.resolve([proposed]);
  await pending;
  assert.equal(notifications, beforeLeaving);
  assert.deepEqual(resource.getSnapshot().items, []);
});
