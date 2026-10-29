package interview.guide.modules.interview.dynamic.service;

import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.modules.interview.dynamic.dto.DynamicRuntimeSnapshot;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.planning.QuestionPlanningService;
import interview.guide.modules.interview.planning.InterviewPlanningContextFactory;
import interview.guide.modules.interview.planning.service.InterviewQuestionPlanPersistenceService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DynamicInterviewCreationService {
  private final CurrentUserProvider currentUser;
  private final InterviewPersistenceService persistence;
  private final InterviewQuestionService questions;
  private final QuestionPlanningService planning;
  private final InterviewQuestionPlanPersistenceService plans;
  private final DynamicMainQuestionGenerator generator;
  private final DynamicInterviewProperties properties;

  public DynamicRuntimeSnapshot create(CreateInterviewRequest request) {
    var ownerId = currentUser.requireUserId();
    String resumeText = request.resumeId() == null ? request.resumeText()
        : persistence.requireOwnedResume(request.resumeId()).getResumeText();
    String skillId = request.skillId() == null ? InterviewDefaults.SKILL_ID : request.skillId();
    String difficulty = request.difficulty() == null
        ? InterviewDefaults.DIFFICULTY : request.difficulty();
    var history = persistence.getHistoricalQuestions(skillId, request.resumeId());
    var skill = questions.resolveSkillForPlanning(skillId, request.customCategories(),
        request.jdText());
    var command = InterviewPlanningContextFactory.create(skillId, skill, request.questionCount(),
        difficulty, resumeText, history);
    var plan = planning.plan(command);
    var policy = properties.snapshot(plan.slots().size());
    String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    plans.createShell(sessionId, ownerId, request.resumeId(), request.llmProvider(), skillId,
        difficulty, plan);
    try {
      var pool = generator.generate(request.llmProvider(), skill, resumeText, plan);
      return plans.finishDynamic(sessionId, ownerId, pool, policy);
    } catch (RuntimeException failure) {
      plans.discardShell(sessionId, ownerId);
      throw failure;
    }
  }
}
