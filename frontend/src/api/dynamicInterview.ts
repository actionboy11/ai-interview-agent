import { request } from './request';
import type { DynamicInterviewApi, DynamicRuntimeSnapshot, DynamicSubmissionReceipt } from '../types/dynamicInterview';

const path = (id: string) => `/api/interview/sessions/${encodeURIComponent(id)}/dynamic`;
export const dynamicInterviewApi: DynamicInterviewApi = {
  get: (id, signal) => request.get<DynamicRuntimeSnapshot>(path(id), { signal }),
  submit: (id, body, signal) => request.post<DynamicSubmissionReceipt>(`${path(id)}/answers`, body, { signal }),
  saveDraft: (id, body, signal) => request.put<void>(`${path(id)}/draft`, body, { signal }),
  complete: (id, expectedVersion, signal) => request.post<DynamicRuntimeSnapshot>(`${path(id)}/complete`, { expectedVersion }, { signal }),
};
