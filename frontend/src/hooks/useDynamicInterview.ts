import { useEffect, useRef, useState } from 'react';
import { dynamicInterviewApi } from '../api/dynamicInterview';
import { DynamicInterviewController } from '../utils/dynamicInterviewState';
import type { DynamicSubmitRequest, SubmissionIntent } from '../types/dynamicInterview';

const initial = { runtime: null, busy: false, uncertain: false, error: '', draftStatus: 'idle' as const };
export function useDynamicInterview(sessionId: string) {
  const current = useRef<DynamicInterviewController | null>(null);
  const [value, setValue] = useState<{ sessionId: string; state: ReturnType<DynamicInterviewController['getSnapshot']> }>({ sessionId, state: initial });
  useEffect(() => {
    const key = `dynamic-interview-pending:${sessionId}`;
    const controller = new DynamicInterviewController(sessionId, dynamicInterviewApi, undefined, {
      read: () => {
        try {
          const body = JSON.parse(sessionStorage.getItem(key) ?? 'null') as DynamicSubmitRequest | null;
          return body && typeof body.requestId === 'string' && Number.isSafeInteger(body.expectedVersion)
            && Number.isSafeInteger(body.questionIndex) && typeof body.answer === 'string'
            && ['ANSWER', 'DONT_KNOW', 'SKIP'].includes(body.submissionIntent) ? body : null;
        } catch { return null; }
      },
      write: body => {
        try { if (body) sessionStorage.setItem(key, JSON.stringify(body)); else sessionStorage.removeItem(key); }
        catch { /* Private browsing may disable storage; in-memory retry remains available. */ }
      },
    });
    current.current = controller;
    const update = () => setValue({ sessionId, state: controller.getSnapshot() });
    const unsubscribe = controller.subscribe(update);
    update();
    controller.startPolling();
    return () => { unsubscribe(); controller.dispose(); if (current.current === controller) current.current = null; };
  }, [sessionId]);
  const state = value.sessionId === sessionId ? value.state : initial;
  return { ...state,
    canAnswer: Boolean(state.runtime?.phase === 'WAITING_ANSWER' && state.runtime.currentQuestion && !state.busy && !state.uncertain),
    submit: (intent: SubmissionIntent, answer: string) => current.current?.submit(intent, answer),
    saveDraft: (answer: string) => current.current?.saveDraft(answer),
    refresh: () => current.current?.refresh(), retry: () => current.current?.retry(),
    complete: () => current.current?.complete(),
  };
}
