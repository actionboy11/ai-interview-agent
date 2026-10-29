package interview.guide.modules.interview.planning.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewRuntimeEntity;
import interview.guide.modules.interview.dynamic.dto.DynamicRuntimeSnapshot;
import interview.guide.modules.interview.dynamic.model.DynamicMainQuestion;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewProperties.PolicySnapshot;
import interview.guide.modules.interview.planning.model.InterviewQuestionPlanEntity;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.repository.InterviewQuestionPlanRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class InterviewQuestionPlanPersistenceService {
  private final InterviewSessionRepository sessions;
  private final InterviewQuestionPlanRepository plans;
  private final UserRepository users;
  private final ResumeRepository resumes;
  private final ObjectMapper mapper;
  private final DynamicInterviewRuntimeRepository runtimes;

  @Transactional
  public void createShell(String sessionId, UUID ownerId, Long resumeId, String provider,
      String skillId, String difficulty, QuestionPlan plan) {
    var session = new InterviewSessionEntity();
    session.setSessionId(sessionId);
    session.setOwner(users.getReferenceById(ownerId));
    session.setResume(resumeId == null ? null : resumes.findByOwnerIdAndId(ownerId, resumeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND)));
    session.setLlmProvider(provider == null ? "default" : provider);
    session.setSkillId(skillId);
    session.setDifficulty(difficulty);
    session.setTotalQuestions(plan.slots().size());
    session.setCurrentQuestionIndex(0);
    sessions.saveAndFlush(session);
    appendPlan(session, plan);
  }

  @Transactional
  public void appendFallback(String sessionId, UUID ownerId, QuestionPlan plan) {
    var session = requireShell(sessionId, ownerId);
    if (session.getQuestionsJson() != null) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "已完成的面试计划不可修改");
    }
    appendPlan(session, plan);
  }

  @Transactional
  public void finish(String sessionId, UUID ownerId, List<InterviewQuestionDTO> questions) {
    var session = requireShell(sessionId, ownerId);
    if (session.getQuestionsJson() != null) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "面试问题已生成");
    }
    session.setTotalQuestions(questions.size());
    session.setQuestionsJson(mapper.writeValueAsString(questions));
    sessions.save(session);
  }

  @Transactional
  public DynamicRuntimeSnapshot finishDynamic(String sessionId, UUID ownerId,
      List<DynamicMainQuestion> pool, PolicySnapshot policy) {
    var session = requireShell(sessionId, ownerId);
    if (session.getQuestionsJson() != null || pool.isEmpty()
        || pool.size() != policy.totalFollowUpBudget()) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "动态主问题池与面试政策不一致");
    }
    var main = pool.getFirst();
    var first = InterviewQuestionDTO.planned(0, main.question(), main.type(), main.category(),
        main.topicSummary(), false, null, main.slotId());
    var runtime = new DynamicInterviewRuntimeEntity(session, mapper.writeValueAsString(pool),
        mapper.writeValueAsString(policy), pool.size(), main.slotId(), policy.maxFollowUpsPerGroup(),
        policy.decisionTimeoutSeconds());
    session.setExecutionMode(InterviewExecutionMode.DYNAMIC_FOLLOW_UP);
    session.setQuestionsJson(mapper.writeValueAsString(List.of(first)));
    session.setTotalQuestions(1);
    session.setCurrentQuestionIndex(0);
    sessions.saveAndFlush(session);
    runtimes.saveAndFlush(runtime);
    return new DynamicRuntimeSnapshot(sessionId, runtime.getVersion(), runtime.getPhase(),
        pool.size(), 1, 0, 0, 1, 1, 0, first, null, null);
  }

  @Transactional
  public void discardShell(String sessionId, UUID ownerId) {
    sessions.findByOwnerIdAndSessionId(ownerId, sessionId)
        .filter(session -> session.getQuestionsJson() == null)
        .ifPresent(session -> {
          plans.deleteAll(plans.findByOwnerIdAndSessionSessionId(ownerId, sessionId));
          plans.flush();
          sessions.delete(session);
        });
  }

  private InterviewSessionEntity requireShell(String sessionId, UUID ownerId) {
    return sessions.findByOwnerIdAndSessionId(ownerId, sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
  }

  private void appendPlan(InterviewSessionEntity session, QuestionPlan plan) {
    var entity = new InterviewQuestionPlanEntity();
    entity.setSession(session);
    entity.setOwner(session.getOwner());
    entity.setPlanVersion(plan.version());
    entity.setPlanningMode(plan.mode());
    entity.setFallbackReason(plan.fallbackReason());
    entity.setPlanJson(mapper.writeValueAsString(plan));
    plans.saveAndFlush(entity);
  }
}
