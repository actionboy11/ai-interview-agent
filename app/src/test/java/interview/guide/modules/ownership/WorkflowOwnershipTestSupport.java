package interview.guide.modules.ownership;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.AppConfigProperties;
import interview.guide.common.config.StorageConfigProperties;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.infrastructure.mapper.InterviewMapperImpl;
import interview.guide.infrastructure.mapper.ResumeMapperImpl;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.interview.messaging.InterviewEvaluationTaskPublisher;
import interview.guide.modules.memory.messaging.AbilityMemoryDerivationTaskPublisher;
import interview.guide.modules.memory.repository.UserAbilityProfileRepository;
import interview.guide.modules.memory.service.AbilityMemoryDeletionService;
import interview.guide.modules.memory.service.AbilityScorePolicy;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.planning.AdaptiveAllocationPolicy;
import interview.guide.modules.interview.planning.QuestionPlanningService;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.QuestionPurpose;
import interview.guide.modules.interview.planning.model.QuestionSlot;
import interview.guide.modules.interview.planning.model.QuestionSourceMode;
import interview.guide.modules.interview.planning.service.InterviewQuestionPlanPersistenceService;
import interview.guide.modules.interview.planning.repository.InterviewQuestionPlanRepository;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewQuestionService;
import interview.guide.modules.interview.service.InterviewQuestionCountService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.interview.service.MemoryDerivationPublicationService;
import interview.guide.modules.resume.messaging.ResumeAnalysisTaskPublisher;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeDeleteService;
import interview.guide.modules.resume.service.ResumeHistoryService;
import interview.guide.modules.resume.service.ResumeParseService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import interview.guide.modules.resume.service.ResumeUploadService;
import interview.guide.modules.resume.deadletter.ResumeAnalysisDeadLetterRepository;
import interview.guide.modules.resume.deadletter.ResumeAnalysisDeadLetterService;
import interview.guide.modules.interview.deadletter.InterviewEvaluationDeadLetterRepository;
import interview.guide.modules.interview.deadletter.InterviewEvaluationDeadLetterService;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = OwnerScopedRepositoryTest.JpaTestApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:owner-workflows;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({ResumePersistenceService.class, ResumeHistoryService.class, ResumeDeleteService.class,
    ResumeUploadService.class, InterviewPersistenceService.class, InterviewSessionService.class,
    InterviewQuestionPlanPersistenceService.class, InterviewQuestionCountService.class,
    MemoryDerivationPublicationService.class, AbilityMemoryDeletionService.class,
    AbilityScorePolicy.class,
    InterviewHistoryService.class, InterviewSessionCache.class, FileStorageService.class,
    FileHashService.class, FileValidationService.class, TransactionalExecutor.class,
    ResumeMapperImpl.class, InterviewMapperImpl.class, ObjectMapper.class,
    AppConfigProperties.class, StorageConfigProperties.class,
    WorkflowOwnershipTestSupport.DeadLetterConfiguration.class,
    ResumeAnalysisDeadLetterService.class, InterviewEvaluationDeadLetterService.class})
abstract class WorkflowOwnershipTestSupport {
  @Configuration(proxyBeanMethods = false)
  @EnableJpaRepositories(basePackageClasses = {ResumeAnalysisDeadLetterRepository.class,
      InterviewEvaluationDeadLetterRepository.class, UserAbilityProfileRepository.class,
      InterviewQuestionPlanRepository.class})
  static class DeadLetterConfiguration {
  }

  @Autowired ResumeAnalysisDeadLetterRepository resumeDeadLetters;
  @Autowired ResumeAnalysisDeadLetterService resumeDeadLetterService;
  @Autowired InterviewEvaluationDeadLetterRepository interviewDeadLetters;
  @Autowired InterviewEvaluationDeadLetterService interviewDeadLetterService;
  @Autowired UserRepository users;
  @Autowired ResumeRepository resumes;
  @Autowired InterviewSessionRepository sessions;
  @Autowired ResumePersistenceService resumePersistence;
  @Autowired ResumeHistoryService resumeHistory;
  @Autowired ResumeDeleteService resumeDelete;
  @Autowired ResumeUploadService resumeUpload;
  @Autowired InterviewPersistenceService interviewPersistence;
  @Autowired InterviewSessionService interviewService;
  @Autowired MemoryDerivationPublicationService memoryPublication;
  @Autowired InterviewHistoryService interviewHistory;
  @Autowired InterviewSessionCache cache;
  @Autowired FileStorageService storage;
  @Autowired ObjectMapper mapper;
  @Autowired AppConfigProperties appConfig;
  @MockitoBean CurrentUserProvider currentUser;
  @MockitoBean RedisService redis;
  @MockitoBean S3Client s3;
  @MockitoBean PdfExportService pdf;
  @MockitoBean ResumeParseService parser;
  @MockitoBean ResumeAnalysisTaskPublisher resumePublisher;
  @MockitoBean InterviewEvaluationTaskPublisher interviewPublisher;
  @MockitoBean AbilityMemoryDerivationTaskPublisher memoryPublisher;
  @MockitoBean InterviewQuestionService questions;
  @MockitoBean QuestionPlanningService planning;
  @MockitoBean AdaptiveAllocationPolicy allocation;
  @MockitoBean AnswerEvaluationService evaluation;
  @MockitoBean LlmProviderRegistry llm;
  UserEntity first;
  UserEntity second;
  Map<String, Object> cachedValues;

  @BeforeEach
  void ownersAndCache() {
    first = users.saveAndFlush(user());
    second = users.saveAndFlush(user());
    when(currentUser.requireUserId()).thenReturn(first.getId());
    when(questions.resolveSkillForPlanning(any(), any(), any())).thenReturn(new SkillDTO(
        "java", "Java", "", List.of(new SkillCategoryDTO("java", "Java", "CORE", null, false)),
        true, null, null, null));
    when(planning.plan(any())).thenReturn(new QuestionPlan(1, PlanningMode.FIXED,
        List.of(new QuestionSlot("slot-01", "java", QuestionPurpose.BASELINE, "mid",
            QuestionSourceMode.DIRECTION, List.of(), List.of(), null)), null));
    when(questions.generateFromPlan(any(), any(), any(), any(), any())).thenReturn(
        List.of(interview.guide.modules.interview.model.InterviewQuestionDTO.create(0,
            "Question", "java", "Java")));
    cachedValues = new HashMap<>();
    when(redis.get(any())).thenAnswer(invocation -> cachedValues.get(invocation.getArgument(0)));
    when(redis.delete(any())).thenAnswer(invocation -> cachedValues.remove(invocation.getArgument(0)) != null);
    doAnswer(invocation -> {
      cachedValues.put(invocation.getArgument(0), invocation.getArgument(1));
      return null;
    }).when(redis).set(any(), any(), any());
  }

  ResumeEntity resume(UserEntity owner, String hash) {
    var resume = new ResumeEntity();
    resume.setOwner(owner);
    resume.setFileHash(hash);
    resume.setOriginalFilename("resume.txt");
    resume.setResumeText("private resume");
    resume.setStorageKey("resumes/legacy.txt");
    return resumes.saveAndFlush(resume);
  }

  private UserEntity user() {
    String name = UUID.randomUUID().toString();
    return UserEntity.builder().username(name).normalizedUsername(name).displayName(name)
        .passwordHash("hash").role(UserRole.USER).status(UserStatus.ACTIVE)
        .createdAt(Instant.now()).updatedAt(Instant.now()).build();
  }
}
