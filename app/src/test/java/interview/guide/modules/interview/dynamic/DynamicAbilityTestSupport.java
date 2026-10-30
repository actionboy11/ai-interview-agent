package interview.guide.modules.interview.dynamic;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.interview.dynamic.model.AssessmentTarget;
import interview.guide.modules.interview.dynamic.model.DynamicEvaluationOutboxEntity;
import interview.guide.modules.interview.dynamic.model.DynamicGroupEvaluation;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewRuntimeEntity;
import interview.guide.modules.interview.dynamic.model.DynamicMainQuestion;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewLifecycle;
import interview.guide.modules.interview.dynamic.service.DynamicAbilityEvidenceSourceService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.FormalAnswer;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.FrozenSnapshot;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.GroupEnds;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.memory.mapper.AbilityMemoryMapper;
import interview.guide.modules.memory.model.UserAbilityEvidenceEntity;
import interview.guide.modules.memory.repository.AbilityProfileProposalRepository;
import interview.guide.modules.memory.repository.UserAbilityEvidenceRepository;
import interview.guide.modules.memory.repository.UserAbilityProfileRepository;
import interview.guide.modules.memory.service.AbilityEvidenceDerivationService;
import interview.guide.modules.memory.service.AbilityMemoryDeletionService;
import interview.guide.modules.memory.service.AbilityProfileCommandService;
import interview.guide.modules.memory.service.AbilityScorePolicy;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = DynamicAbilityTestSupport.TestApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:dynamic-ability;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop"
})
abstract class DynamicAbilityTestSupport {
  @Configuration(proxyBeanMethods = false)
  @EntityScan("interview.guide.modules")
  @EnableJpaRepositories("interview.guide.modules")
  @Import({AbilityEvidenceDerivationService.class, AbilityScorePolicy.class,
      AbilityProfileCommandService.class, AbilityMemoryDeletionService.class,
      DynamicInterviewLifecycle.class, DynamicAbilityEvidenceSourceService.class})
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      TransactionAutoConfiguration.class})
  static class TestApplication {
    @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
  }

  @Autowired EntityManager em;
  @Autowired TransactionTemplate tx;
  @Autowired ObjectMapper mapper;
  @Autowired AbilityEvidenceDerivationService derivation;
  @Autowired AbilityProfileCommandService commands;
  @Autowired AbilityMemoryDeletionService deletion;
  @Autowired AbilityProfileProposalRepository proposals;
  @Autowired UserAbilityEvidenceRepository evidence;
  @Autowired UserAbilityProfileRepository profiles;

  record Fixture(UUID ownerId, Long sessionDbId, String sessionId, List<Long> answerIds) {}
  record GroupSpec(int answerCount, Integer score, SubmissionIntent intent) {}

  Fixture fixture(GroupSpec... specs) {
    return tx.execute(status -> {
      var owner = UserEntity.builder().username(UUID.randomUUID().toString())
          .normalizedUsername(UUID.randomUUID().toString()).passwordHash("test-hash")
          .displayName("测试").role(UserRole.USER).status(UserStatus.ACTIVE)
          .createdAt(Instant.now()).updatedAt(Instant.now()).build();
      em.persist(owner);
      var session = new InterviewSessionEntity();
      session.setOwner(owner);
      session.setSessionId(UUID.randomUUID().toString());
      session.setSkillId("java-backend");
      session.setDifficulty("mid");
      session.setExecutionMode(InterviewExecutionMode.DYNAMIC_FOLLOW_UP);
      session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
      session.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
      em.persist(session);
      var mains = new ArrayList<DynamicMainQuestion>();
      var questions = new ArrayList<InterviewQuestionDTO>();
      var formal = new ArrayList<FormalAnswer>();
      var groups = new ArrayList<DynamicGroupEvaluation>();
      var details = new ArrayList<InterviewReportDTO.QuestionEvaluation>();
      int index = 0;
      for (int ordinal = 0; ordinal < specs.length; ordinal++) {
        var spec = specs[ordinal];
        String slot = "slot-" + ordinal;
        String question = "主问题 " + ordinal;
        mains.add(new DynamicMainQuestion(slot, question, "JAVA", "Java", "集合观察",
            List.of(new AssessmentTarget("t1", "线程安全"), new AssessmentTarget("t2", "扩容")),
            "senior", List.of()));
        var indexes = new ArrayList<Integer>();
        int mainIndex = index;
        int published = Math.max(1, spec.answerCount());
        for (int offset = 0; offset < published; offset++, index++) {
          String text = offset == 0 ? question : "追问 " + index;
          questions.add(InterviewQuestionDTO.planned(index, text, "JAVA", "Java",
              "集合观察", offset > 0, offset > 0 ? mainIndex : null, slot));
          String answerText = null;
          Integer itemScore = null;
          if (offset < spec.answerCount()) {
            var answer = new InterviewAnswerEntity();
            answer.setSession(session);
            answer.setQuestionIndex(index);
            answer.setQuestion(text);
            answer.setCategoryKey("JAVA");
            answer.setCategoryLabel("Java");
            answerText = spec.intent() == SubmissionIntent.ANSWER ? "正式回答 " + index : null;
            answer.setUserAnswer(answerText);
            answer.setSubmissionIntent(spec.intent());
            answer.setSubmissionOwnerId(owner.getId());
            answer.setRequestId(UUID.randomUUID());
            answer.setRequestFingerprint("a".repeat(64));
            itemScore = spec.intent() == SubmissionIntent.SKIP ? null : 20 + index;
            answer.setScore(itemScore);
            answer.setFeedback("逐题评价不能作为组评分");
            em.persist(answer);
            em.flush();
            formal.add(new FormalAnswer(answer.getId(), index, text, "JAVA", "Java", answerText,
                spec.intent(), answer.getRequestId()));
            if (spec.intent() != SubmissionIntent.SKIP) { indexes.add(index); }
          }
          details.add(new InterviewReportDTO.QuestionEvaluation(index, text, "JAVA", "Java",
              answerText, itemScore, itemScore == null ? "未评估" : "逐题观察"));
        }
        groups.add(new DynamicGroupEvaluation(slot, spec.score(),
            spec.score() == null ? "未评估" : "线程安全的具体观察 " + ordinal,
            indexes, spec.score() == null ? List.of() : List.of("t1")));
      }
      session.setQuestionsJson(mapper.writeValueAsString(questions));
      var runtime = new DynamicInterviewRuntimeEntity(session, mapper.writeValueAsString(mains),
          "{\"schemaVersion\":1}", specs.length, "slot-0");
      em.persist(runtime);
      var frozen = new FrozenSnapshot(1, session.getSessionId(), owner.getId(), 5L, mains, null,
          questions, formal, new GroupEnds(1, Map.of()));
      var outbox = new DynamicEvaluationOutboxEntity(runtime, 5L,
          mapper.writeValueAsString(frozen), Instant.now());
      em.persist(outbox);
      session.setEvaluationMessageId(outbox.getMessageId().toString());
      var valid = groups.stream().filter(group -> group.score() != null).toList();
      Integer score = valid.isEmpty() ? null : (int) Math.round(valid.stream()
          .mapToInt(DynamicGroupEvaluation::score).average().orElseThrow());
      session.setReportJson(mapper.writeValueAsString(new InterviewReportDTO(session.getSessionId(),
          questions.size(), score, List.of(new InterviewReportDTO.CategoryScore("JAVA", "Java",
              score, valid.size())), details, "正式组评价", List.of(), List.of(), List.of(), groups,
          InterviewExecutionMode.DYNAMIC_FOLLOW_UP, 1, 5L)));
      return new Fixture(owner.getId(), session.getId(), session.getSessionId(), formal.stream()
          .map(FormalAnswer::answerId).toList());
    });
  }

  List<UserAbilityEvidenceEntity> items(Fixture f) {
    return evidence.findByOwnerIdAndInterviewSessionId(f.ownerId(), f.sessionDbId());
  }

  List<Map<String, Object>> responses(Fixture f) {
    return tx.execute(status -> items(f).stream().map(item -> mapper.convertValue(
        Mappers.getMapper(AbilityMemoryMapper.class).toResponse(item),
        new TypeReference<Map<String, Object>>() {})).toList());
  }
}
