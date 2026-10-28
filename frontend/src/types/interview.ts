// 面试相关类型定义

import type { CategoryDTO } from '../api/skill';

export interface InterviewSession {
  sessionId: string;
  resumeText: string;
  totalQuestions: number;
  mainQuestionCount?: number | null;
  followUpQuestionCount?: number | null;
  currentQuestionIndex: number;
  questions: InterviewQuestion[];
  status: 'CREATED' | 'IN_PROGRESS' | 'COMPLETED' | 'EVALUATED';
}

export interface InterviewQuestion {
  questionIndex: number;
  isFollowUp?: boolean;
  parentQuestionIndex?: number | null;
  slotId?: string | null;
  question: string;
  type: string;
  category: string;
  userAnswer: string | null;
  score: number | null;
  feedback: string | null;
}

export type QuestionPurpose = 'MANDATORY' | 'BASELINE' | 'WEAKNESS_REINFORCEMENT'
  | 'STRENGTH_CALIBRATION' | 'EXPLORATION';

export interface QuestionPlanSummary {
  available: boolean;
  mode: 'ADAPTIVE' | 'FIXED' | null;
  purposeCounts: Record<QuestionPurpose, number>;
  questions: Array<{
    questionIndex: number;
    slotId: string;
    categoryKey: string;
    purpose: QuestionPurpose;
    reason: string;
  }>;
}

export interface CreateInterviewRequest {
  resumeText: string;
  questionCount: number;
  resumeId?: number;
  forceCreate?: boolean;
  llmProvider?: string;
  skillId: string;
  difficulty?: string;
  customCategories?: CategoryDTO[];
  jdText?: string;
}

export interface SubmitAnswerRequest {
  sessionId: string;
  questionIndex: number;
  answer: string;
}

export interface SubmitAnswerResponse {
  hasNextQuestion: boolean;
  nextQuestion: InterviewQuestion | null;
  currentIndex: number;
  totalQuestions: number;
}

export interface CurrentQuestionResponse {
  completed: boolean;
  question?: InterviewQuestion;
  message?: string;
}

export interface InterviewReport {
  sessionId: string;
  totalQuestions: number;
  overallScore: number;
  categoryScores: CategoryScore[];
  questionDetails: QuestionEvaluation[];
  overallFeedback: string;
  strengths: string[];
  improvements: string[];
  referenceAnswers: ReferenceAnswer[];
}

export interface CategoryScore {
  category: string;
  categoryKey: string;
  categoryLabel: string;
  score: number;
  questionCount: number;
}

export interface QuestionEvaluation {
  questionIndex: number;
  question: string;
  category: string;
  categoryKey: string;
  categoryLabel: string;
  userAnswer: string;
  score: number;
  feedback: string;
}

export interface ReferenceAnswer {
  questionIndex: number;
  question: string;
  referenceAnswer: string;
  keyPoints: string[];
}
