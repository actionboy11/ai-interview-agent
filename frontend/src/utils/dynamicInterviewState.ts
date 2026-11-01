import type { DynamicInterviewApi, DynamicRuntimeSnapshot, DynamicSubmitRequest, SubmissionIntent } from '../types/dynamicInterview.ts';

export function acceptSnapshot(sessionId: string, current: DynamicRuntimeSnapshot | null, next: DynamicRuntimeSnapshot) {
  return next.sessionId !== sessionId || (current && next.version < current.version) ? current : next;
}
export function mainProgress(runtime: DynamicRuntimeSnapshot) {
  return { completed: runtime.completedMainCount, planned: runtime.plannedMainQuestionCount, current: runtime.currentMainOrdinal };
}
interface State {
  runtime: DynamicRuntimeSnapshot | null;
  busy: boolean;
  uncertain: boolean;
  error: string;
  draftStatus: 'idle' | 'saving' | 'saved';
}
interface PendingStore {
  read: () => DynamicSubmitRequest | null;
  write: (body: DynamicSubmitRequest | null) => void;
}

/** Owns one session only. Disposing fences every inflight response, including drafts. */
export class DynamicInterviewController {
  readonly api: DynamicInterviewApi;
  private readonly sessionId: string;
  private readonly uuid: () => string;
  private readonly store?: PendingStore;
  private state: State = { runtime: null, busy: false, uncertain: false, error: '', draftStatus: 'idle' };
  private pending: DynamicSubmitRequest | null;
  private active = true;
  private abort = new AbortController();
  private listeners = new Set<() => void>();
  private draftRevision = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  constructor(sessionId: string, api: DynamicInterviewApi, uuid: () => string = () => crypto.randomUUID(), store?: PendingStore) {
    this.sessionId = sessionId;
    this.api = api;
    this.uuid = uuid;
    this.store = store;
    this.pending = store?.read() ?? null;
    this.state.uncertain = Boolean(this.pending);
  }
  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  private publish(patch: Partial<State>) {
    if (!this.active) return;
    this.state = { ...this.state, ...patch };
    this.listeners.forEach(listener => listener());
  }
  private receive(next: DynamicRuntimeSnapshot) {
    if (!this.active) return;
    const runtime = acceptSnapshot(this.sessionId, this.state.runtime, next);
    if (runtime !== next) return;
    if (this.pending && runtime.version > this.pending.expectedVersion) {
      this.pending = null;
      this.store?.write(null);
    }
    this.publish({ runtime, uncertain: Boolean(this.pending), error: '' });
  }
  canAnswer() {
    return this.active && !this.state.busy && !this.pending
      && this.state.runtime?.phase === 'WAITING_ANSWER' && Boolean(this.state.runtime.currentQuestion);
  }
  async refresh() {
    try { this.receive(await this.api.get(this.sessionId, this.abort.signal)); }
    catch (error) { this.publish({ error: error instanceof Error ? error.message : '连接失败，请恢复连接后重试' }); }
  }
  startPolling() {
    const poll = async () => {
      await this.refresh();
      if (this.active && this.state.runtime?.phase !== 'COMPLETED') this.timer = setTimeout(poll, 2000);
    };
    void poll();
  }
  async submit(intent: SubmissionIntent, answer: string) {
    if (!this.canAnswer() || (intent === 'ANSWER' && !answer.trim())) return;
    const runtime = this.state.runtime!;
    this.pending = { requestId: this.uuid(), questionIndex: runtime.currentQuestion!.questionIndex,
      expectedVersion: runtime.version, submissionIntent: intent, answer: answer.trim() };
    this.store?.write(this.pending);
    await this.sendPending();
  }
  private async sendPending() {
    if (!this.active || !this.pending || this.state.busy) return;
    const body = this.pending;
    this.draftRevision += 1;
    this.publish({ busy: true, error: '', draftStatus: 'idle' });
    try {
      const receipt = await this.api.submit(this.sessionId, body, this.abort.signal);
      if (!this.active) return;
      if (receipt.requestId !== body.requestId) throw new Error('提交回执不匹配，请恢复查询');
      this.pending = null;
      this.store?.write(null);
      this.receive(receipt.runtime);
      this.publish({ uncertain: false });
    } catch (error) {
      this.publish({ uncertain: true, error: `${error instanceof Error ? error.message : '连接中断'}。提交结果尚未确认，请恢复查询或安全重试。` });
    } finally { this.publish({ busy: false }); }
  }
  async retry() {
    if (this.state.busy) return;
    // Query first: a disconnected POST may already have committed.
    await this.refresh();
    if (this.pending) await this.sendPending();
  }
  async saveDraft(answer: string) {
    if (!this.canAnswer()) return;
    const runtime = this.state.runtime!;
    const revision = ++this.draftRevision;
    this.publish({ draftStatus: 'saving' });
    try {
      await this.api.saveDraft(this.sessionId, { questionIndex: runtime.currentQuestion!.questionIndex,
        expectedVersion: runtime.version, answer }, this.abort.signal);
      if (revision === this.draftRevision && this.canAnswer() && this.state.runtime?.version === runtime.version) {
        this.publish({ draftStatus: 'saved' });
      }
    } catch (error) {
      if (revision === this.draftRevision) this.publish({ draftStatus: 'idle', error: error instanceof Error ? error.message : '草稿保存失败' });
    }
  }
  async complete() {
    if (!this.active || this.state.busy || !this.state.runtime || this.state.runtime.phase === 'COMPLETED') return;
    // Resolve uncertain submissions before a versioned completion command.
    await this.refresh();
    if (this.pending || !this.active || !this.state.runtime) return;
    this.draftRevision += 1;
    this.publish({ busy: true, draftStatus: 'idle' });
    try { this.receive(await this.api.complete(this.sessionId, this.state.runtime.version, this.abort.signal)); }
    catch (error) { this.publish({ error: `${error instanceof Error ? error.message : '交卷连接中断'}。请恢复查询确认交卷状态。` }); }
    finally { this.publish({ busy: false }); }
  }
  dispose() {
    this.active = false;
    this.draftRevision += 1;
    clearTimeout(this.timer);
    this.abort.abort();
    this.listeners.clear();
  }
}
