import type { InterviewQuestion } from '../types/interview';

interface InterviewQuestionCounts {
  totalQuestions?: number | null;
  mainQuestionCount?: number | null;
  followUpQuestionCount?: number | null;
}

type QuestionMetadata = Pick<InterviewQuestion, 'questionIndex'> & {
  isFollowUp?: boolean;
  parentQuestionIndex?: number | null;
};

function isCount(value: number | null | undefined): value is number {
  return Number.isSafeInteger(value) && value! >= 0;
}

function isQuestionMetadata(value: unknown): value is QuestionMetadata {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    && 'questionIndex' in value && typeof value.questionIndex === 'number'
    && isCount(value.questionIndex);
}

export function formatInterviewQuestionCounts(input: InterviewQuestionCounts): string {
  const { totalQuestions, mainQuestionCount, followUpQuestionCount } = input;
  if (!isCount(totalQuestions)) return '题数未知';
  if (isCount(mainQuestionCount) && isCount(followUpQuestionCount)
    && mainQuestionCount + followUpQuestionCount === totalQuestions) {
    return `${mainQuestionCount}道主问题 + ${followUpQuestionCount}道追问，共${totalQuestions}个作答项`;
  }
  return `${totalQuestions}个作答项`;
}

export function getInterviewFollowUpLabel(
  questionIndex: number,
  questions?: unknown,
): string | null {
  if (!Array.isArray(questions)) return null;
  const question = questions.find(
    (item): item is QuestionMetadata => isQuestionMetadata(item) && item.questionIndex === questionIndex,
  );
  if (question?.isFollowUp !== true || !isCount(question.parentQuestionIndex)
    || question.parentQuestionIndex === questionIndex) return null;
  const parent = questions.find(
    (item): item is QuestionMetadata => isQuestionMetadata(item)
      && item.questionIndex === question.parentQuestionIndex,
  );
  if (parent?.isFollowUp !== false) return null;
  // Use the original answer index, matching the question numbers elsewhere in the UI.
  return `第${parent.questionIndex + 1}道主问题的追问`;
}
