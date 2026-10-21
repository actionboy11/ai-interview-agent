import { request } from './request';
import type { AbilityEvidence, AbilityLevel, AbilityProfile, AbilityProposal } from '../types/memory';

const base = '/api/ability-memory';

export const memoryApi = {
  profiles: (skillId?: string) => request.get<AbilityProfile[]>(`${base}/profiles`, { params: skillId ? { skillId } : undefined }),
  profile: (id: number) => request.get<AbilityProfile>(`${base}/profiles/${id}`),
  profileEvidence: (id: number) => request.get<AbilityEvidence[]>(`${base}/profiles/${id}/evidence`),
  proposals: (sessionId?: string) => request.get<AbilityProposal[]>(`${base}/proposals`, { params: sessionId ? { sessionId } : undefined }),
  proposal: (id: number) => request.get<AbilityProposal>(`${base}/proposals/${id}`),
  proposalEvidence: (id: number) => request.get<AbilityEvidence[]>(`${base}/proposals/${id}/evidence`),
  accept: (id: number, version: number) => request.post<AbilityProfile>(`${base}/proposals/${id}/accept`, { version }),
  acceptWithEdit: (id: number, version: number, level: AbilityLevel, summary: string) =>
    request.post<AbilityProfile>(`${base}/proposals/${id}/accept-with-edit`, { version, level, summary }),
  reject: (id: number, version: number) => request.post<void>(`${base}/proposals/${id}/reject`, { version }),
  disable: (id: number, version: number) => request.post<AbilityProfile>(`${base}/profiles/${id}/disable`, { version }),
  enable: (id: number, version: number) => request.post<AbilityProfile>(`${base}/profiles/${id}/enable`, { version }),
};
