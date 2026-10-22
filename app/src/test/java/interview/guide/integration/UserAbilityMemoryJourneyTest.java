package interview.guide.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.auth.mapper.AuthResponseMapperImpl;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.security.CurrentUser;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.auth.service.AdminAccountService;
import interview.guide.modules.auth.service.AuthService;
import interview.guide.modules.auth.service.AuthUserQueryService;
import interview.guide.modules.auth.service.CreateAdminUserCommand;
import interview.guide.modules.auth.service.InviteCodeService;
import interview.guide.modules.auth.service.UsernameNormalizer;
import interview.guide.modules.interview.InterviewController;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.planning.QuestionPlanningService;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.interview.planning.repository.InterviewQuestionPlanRepository;
import interview.guide.modules.interview.planning.service.InterviewQuestionPlanPersistenceService;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.memory.mapper.AbilityMemoryMapperImpl;
import interview.guide.modules.memory.model.AbilityLevel;
import interview.guide.modules.memory.repository.AbilityProfileProposalRepository;
import interview.guide.modules.memory.repository.UserAbilityProfileRepository;
import interview.guide.modules.memory.service.AbilityEvidenceDerivationService;
import interview.guide.modules.memory.service.AbilityMemoryDeletionService;
import interview.guide.modules.memory.service.AbilityProfileCommandService;
import interview.guide.modules.memory.service.AbilityProfileQueryService;
import interview.guide.modules.memory.service.AbilityScorePolicy;
import interview.guide.modules.memory.service.DerivationResult;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = UserAbilityMemoryJourneyTest.TestApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:ability-journey;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DisplayName("用户能力记忆完整旅程")
class UserAbilityMemoryJourneyTest {
  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @EntityScan("interview.guide.modules")
  @EnableJpaRepositories("interview.guide.modules")
  @ComponentScan(basePackageClasses = QuestionPlanningService.class)
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      TransactionAutoConfiguration.class})
  @Import({AdminAccountService.class, AuthService.class, InviteCodeService.class,
      UsernameNormalizer.class, AuthUserQueryService.class, AuthResponseMapperImpl.class,
      CurrentUserProvider.class, AbilityEvidenceDerivationService.class,
      AbilityMemoryDeletionService.class, InterviewPersistenceService.class,
      AbilityProfileCommandService.class, AbilityProfileQueryService.class,
      AbilityScorePolicy.class, AbilityMemoryMapperImpl.class, InterviewController.class,
      GlobalExceptionHandler.class})
  static class TestApplication {
    @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
  }

  @Autowired AdminAccountService accounts;
  @Autowired AuthService auth;
  @Autowired InviteCodeService invites;
  @Autowired CurrentUserProvider currentUser;
  @Autowired AbilityEvidenceDerivationService derivation;
  @Autowired AbilityProfileCommandService decisions;
  @MockitoSpyBean AbilityProfileQueryService memory;
  @Autowired QuestionPlanningService planning;
  @Autowired InterviewQuestionPlanPersistenceService persistence;
  @Autowired InterviewQuestionPlanRepository plans;
  @Autowired InterviewSessionRepository sessions;
  @Autowired AbilityProfileProposalRepository proposals;
  @Autowired UserAbilityProfileRepository profiles;
  @Autowired ResumeRepository resumes;
  @Autowired WebApplicationContext web;
  @MockitoBean InterviewSkillService skills;
  @MockitoBean InterviewSessionService interviewSessions;
  @MockitoBean InterviewHistoryService history;
  @Autowired InterviewPersistenceService interviewPersistence;

  @AfterEach
  void clearIdentity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("管理员邀请双用户，确认后的弱项影响下一场且解释快照隔离")
  void invitedOwnersSeeOnlyConfirmedSnapshot() throws Exception {
    UserEntity admin = accounts.createUser(new CreateAdminUserCommand(
        "journey-admin", "journey-password", "Admin", UserRole.ADMIN));
    UserEntity a = invited(admin, "journey-a");
    UserEntity b = invited(admin, "journey-b");
    assertThat(auth.authenticate("journey-a", "journey-password").getId()).isEqualTo(a.getId());
    assertThat(auth.authenticate("journey-b", "journey-password").getId()).isEqualTo(b.getId());

    ResumeEntity aResume = resume(a, "same-content-hash");
    ResumeEntity bResume = resume(b, "same-content-hash");
    assertThat(resumes.findByOwnerIdAndId(a.getId(), bResume.getId())).isEmpty();
    assertThat(resumes.findByOwnerIdAndId(b.getId(), aResume.getId())).isEmpty();

    String evaluated = UUID.randomUUID().toString();
    authenticate(a);
    InterviewSessionEntity first = interviewPersistence.saveSession(evaluated, null, 1,
        List.of(InterviewQuestionDTO.planned(0, "Explain index usage", "MYSQL", "MySQL",
            "Index usage", false, null, "slot-01")), null, "java-backend", "mid");
    interviewPersistence.saveAnswer(evaluated, 0, "Explain index usage", "MYSQL", "MySQL",
        "uncertain answer", 30, "Needs practice");
    assertThat(interviewPersistence.saveReport(evaluated, new InterviewReportDTO(evaluated,
        1, 30, List.of(new InterviewReportDTO.CategoryScore("MYSQL", "MySQL", 30, 1)),
        List.of(new InterviewReportDTO.QuestionEvaluation(0, "Explain index usage", "MYSQL",
            "MySQL", "uncertain answer", 30, "Needs practice")), "Practice indexes",
        List.of(), List.of("Index usage"), List.of()), UUID.randomUUID().toString()))
        .isEqualTo(InterviewPersistenceService.CompletionResult.CREATED);
    assertThat(derivation.derive(evaluated, UUID.randomUUID()))
        .isEqualTo(DerivationResult.CREATED);
    assertThat(derivation.derive(evaluated, UUID.randomUUID()))
        .isEqualTo(DerivationResult.ALREADY_COMPLETED);
    var proposed = proposals.findByOwnerIdAndInterviewSessionId(a.getId(), first.getId());
    assertThat(proposed).hasSize(1);
    assertThat(memory.profiles(a.getId(), "java-backend")).isEmpty();

    when(skills.calculateAllocation(anyList(), anyInt()))
        .thenReturn(Map.of("MYSQL", 4, "JAVA", 3, "REDIS", 3));
    authenticate(a);
    assertThat(planning.plan(command()).mode()).isEqualTo(PlanningMode.FIXED);
    decisions.acceptWithEdit(a.getId(), proposed.getFirst().getId(),
        proposed.getFirst().getVersion(), AbilityLevel.WEAK, "MYSQL_INTERNAL_MEMORY_SECRET");
    assertThat(memory.profiles(b.getId(), "java-backend")).isEmpty();
    var selected = planning.plan(command());
    assertThat(selected.mode()).isEqualTo(PlanningMode.ADAPTIVE);
    assertThat(selected.slots()).anySatisfy(slot -> {
      assertThat(slot.categoryKey()).isEqualTo("MYSQL");
      assertThat(slot.purpose().name()).isEqualTo("WEAKNESS_REINFORCEMENT");
    });

    String next = UUID.randomUUID().toString();
    persistence.createShell(next, a.getId(), aResume.getId(), null, "java-backend", "mid", selected);
    List<InterviewQuestionDTO> generated = selected.slots().stream()
        .map(slot -> InterviewQuestionDTO.planned(selected.slots().indexOf(slot),
            "Question for " + slot.categoryKey(), slot.categoryKey(), slot.categoryKey(),
            null, false, null, slot.slotId())).toList();
    persistence.finish(next, a.getId(), generated);
    MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).build();
    String body = mvc.perform(get("/api/interview/sessions/{id}/plan", next))
        .andReturn().getResponse().getContentAsString();
    assertThat(body).contains("WEAKNESS_REINFORCEMENT", "MYSQL", "巩固");
    assertThat(body).doesNotContain("MYSQL_INTERNAL_MEMORY_SECRET", "focusTopics", "avoidedTopics");
    assertThat(plans.findFirstByOwnerIdAndSessionSessionIdOrderByIdDesc(a.getId(), next)).isPresent();

    profiles.findByOwnerIdAndSkillIdAndCategoryKey(a.getId(), "java-backend", "MYSQL")
        .ifPresent(profile -> { profile.setSummary("UPDATED_PRIVATE_MEMORY"); profiles.saveAndFlush(profile); });
    assertThat(mvc.perform(get("/api/interview/sessions/{id}/plan", next))
        .andReturn().getResponse().getContentAsString()).isEqualTo(body);
    authenticate(b);
    assertThat(mvc.perform(get("/api/interview/sessions/{id}/plan", next))
        .andReturn().getResponse().getContentAsString()).doesNotContain("WEAKNESS_REINFORCEMENT");
    assertThat(planning.plan(command()).mode()).isEqualTo(PlanningMode.FIXED);

    authenticate(a);
    doThrow(new IllegalStateException("temporary memory read failure"))
        .when(memory).profiles(a.getId(), "java-backend");
    assertThat(planning.plan(command()).mode()).isEqualTo(PlanningMode.FIXED);
  }

  @Test
  @DisplayName("旧会话没有计划快照时返回空摘要且不泄露其他用户会话")
  void legacyAndForeignSessionAreSafe() throws Exception {
    UserEntity admin = accounts.createUser(new CreateAdminUserCommand(
        "legacy-admin", "journey-password", "Admin", UserRole.ADMIN));
    UserEntity owner = invited(admin, "legacy-owner");
    UserEntity other = invited(admin, "legacy-other");
    String id = UUID.randomUUID().toString();
    evaluatedSession(owner, id);
    MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).build();
    authenticate(owner);
    String legacy = mvc.perform(get("/api/interview/sessions/{id}/plan", id))
        .andReturn().getResponse().getContentAsString();
    assertThat(legacy).contains("\"available\":false", "\"questions\":[]");
    authenticate(other);
    String denied = mvc.perform(get("/api/interview/sessions/{id}/plan", id))
        .andReturn().getResponse().getContentAsString();
    assertThat(denied).contains("\"code\":3001").doesNotContain("\"available\":false");
  }

  private UserEntity invited(UserEntity admin, String username) {
    String code = invites.issue(Duration.ofHours(1), admin.getId()).rawCode();
    return invites.register(code, username, "journey-password", username);
  }

  private void authenticate(UserEntity user) {
    var principal = auth.authenticate(user.getUsername(), "journey-password");
    var current = new CurrentUser(principal.getId(),
        principal.getUsername(), principal.getDisplayName(), principal.getRole(), false);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(current, null, List.of()));
    assertThat(currentUser.requireUserId()).isEqualTo(user.getId());
  }

  private ResumeEntity resume(UserEntity owner, String hash) {
    var value = new ResumeEntity();
    value.setOwner(owner);
    value.setFileHash(hash);
    value.setOriginalFilename("resume.txt");
    value.setStorageKey("resumes/" + owner.getId());
    value.setResumeText("MYSQL experience");
    return resumes.saveAndFlush(value);
  }

  private InterviewSessionEntity evaluatedSession(UserEntity owner, String id) {
    var value = new InterviewSessionEntity();
    value.setOwner(owner);
    value.setSessionId(id);
    value.setSkillId("java-backend");
    value.setDifficulty("mid");
    value.setQuestionsJson("[]");
    value.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    value.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    return sessions.saveAndFlush(value);
  }

  private QuestionPlanningCommand command() {
    return new QuestionPlanningCommand("java-backend", List.of(
        new SkillCategoryDTO("MYSQL", "MySQL", "CORE", null, false),
        new SkillCategoryDTO("JAVA", "Java", "CORE", null, false),
        new SkillCategoryDTO("REDIS", "Redis", "CORE", null, false)),
        10, "mid", false, Map.of(), List.of(), Set.of());
  }
}
