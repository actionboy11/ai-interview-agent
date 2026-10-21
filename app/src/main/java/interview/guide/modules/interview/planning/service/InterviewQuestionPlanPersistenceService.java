package interview.guide.modules.interview.planning.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
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
