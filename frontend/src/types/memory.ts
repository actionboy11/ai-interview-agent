export type AbilityLevel = 'WEAK' | 'DEVELOPING' | 'COMPETENT' | 'PROFICIENT' | 'STRONG';
export type AbilityProfileStatus = 'ACTIVE' | 'DISABLED' | 'NEEDS_REVIEW';
export type AbilityProposalStatus = 'PROPOSED' | 'ACCEPTED' | 'ACCEPTED_WITH_EDIT' | 'REJECTED';

export interface AbilityProfile {
  id: number;
  skillId: string;
  categoryKey: string;
  level: AbilityLevel;
  score: number;
  confidence: number;
  summary: string;
  confirmedEvidenceCount: number;
  status: AbilityProfileStatus;
  manuallyEdited: boolean;
  lastEvaluatedAt: string | null;
  lastConfirmedAt: string | null;
  version: number;
}

export interface AbilityProposal {
  id: number;
  interviewSessionId: string;
  skillId: string;
  categoryKey: string;
  previousLevel: AbilityLevel | null;
  previousScore: number | null;
  proposedLevel: AbilityLevel;
  proposedScore: number;
  proposedSummary: string;
  finalLevel: AbilityLevel | null;
  finalScore: number | null;
  finalSummary: string | null;
  status: AbilityProposalStatus;
  createdAt: string;
  decidedAt: string | null;
  version: number;
}

export interface AbilityEvidence {
  id: number;
  interviewSessionId: string;
  proposalId: number;
  proposalStatus: AbilityProposalStatus;
  skillId: string;
  categoryKey: string;
  topicSummary: string;
  questionScore: number;
  difficulty: string;
  feedbackSummary: string;
  occurredAt: string;
}
