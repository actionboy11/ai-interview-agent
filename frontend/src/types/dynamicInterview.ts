import type { InterviewQuestion } from './interview';

export type InterviewExecutionMode = 'LEGACY_FIXED' | 'DYNAMIC_FOLLOW_UP';
export type SubmissionIntent = 'ANSWER' | 'DONT_KNOW' | 'SKIP';
export interface DynamicRuntimeSnapshot {
  sessionId: string;
  version: number;
  phase: 'WAITING_ANSWER' | 'DECISION_PENDING' | 'COMPLETED';
  plannedMainQuestionCount: number;
  currentMainOrdinal: number;
  completedMainCount: number;
  usedFollowUps: number;
  totalQuestions: number;
  mainQuestionCount: number;
  followUpQuestionCount: number;
  currentQuestion: InterviewQuestion | null;
  pendingDecisionId: string | null;
  noticeCode: string | null;
}
export interface DynamicSubmitRequest {
  requestId: string;
  questionIndex: number;
  expectedVersion: number;
  submissionIntent: SubmissionIntent;
  answer: string;
}
export interface DynamicSubmissionReceipt {
  requestId: string;
  decisionId: string | null;
  runtime: DynamicRuntimeSnapshot;
}
export interface DynamicDraftRequest {
  questionIndex: number;
  expectedVersion: number;
  answer: string;
}
export interface DynamicInterviewApi {
  get: (sessionId: string, signal?: AbortSignal) => Promise<DynamicRuntimeSnapshot>;
  submit: (sessionId: string, body: DynamicSubmitRequest, signal?: AbortSignal) => Promise<DynamicSubmissionReceipt>;
  saveDraft: (sessionId: string, body: DynamicDraftRequest, signal?: AbortSignal) => Promise<void>;
  complete: (sessionId: string, expectedVersion: number, signal?: AbortSignal) => Promise<DynamicRuntimeSnapshot>;
}
export interface DynamicGroupEvaluation {
  slotId: string;
  score: number | null;
  evidenceSummary: string;
  supportingQuestionIndexes: number[];
  observedTargetIds: string[];
}
