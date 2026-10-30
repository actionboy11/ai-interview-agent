package interview.guide.modules.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewLifecycle;
import interview.guide.modules.memory.model.AbilityLevel;
import interview.guide.modules.memory.model.AbilityProfileProposalEntity;
import interview.guide.modules.memory.model.AbilityProfileStatus;
import interview.guide.modules.memory.model.AbilityProposalStatus;
import interview.guide.modules.memory.model.UserAbilityEvidenceEntity;
import interview.guide.modules.memory.model.UserAbilityProfileEntity;
import interview.guide.modules.memory.mapper.AbilityMemoryMapper;
import interview.guide.modules.memory.repository.AbilityProfileProposalRepository;
import interview.guide.modules.memory.repository.UserAbilityEvidenceRepository;
import interview.guide.modules.memory.repository.UserAbilityProfileRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.support.TransactionTemplate;
import org.mapstruct.factory.Mappers;

@SpringBootTest(classes = AbilityProfileCommandServiceTest.TestApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:ability-decisions;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DisplayName("能力画像确认决策")
class AbilityProfileCommandServiceTest {
  @Configuration(proxyBeanMethods = false)
  @EntityScan("interview.guide.modules")
  @EnableJpaRepositories("interview.guide.modules")
  @Import({AbilityProfileCommandService.class, AbilityMemoryDeletionService.class,
      DynamicInterviewLifecycle.class,
      AbilityProfileQueryService.class, AbilityScorePolicy.class})
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      TransactionAutoConfiguration.class})
  static class TestApplication {
    @Bean
    AbilityMemoryMapper memoryMapper() {
      return Mappers.getMapper(AbilityMemoryMapper.class);
    }
  }

  @Autowired EntityManager em;
  @Autowired TransactionTemplate transactions;
  @Autowired AbilityProfileCommandService commands;
  @Autowired AbilityMemoryDeletionService deletions;
  @Autowired AbilityProfileQueryService queries;
  @Autowired AbilityProfileProposalRepository proposals;
  @Autowired UserAbilityProfileRepository profiles;
  @Autowired UserAbilityEvidenceRepository evidence;

  @Test
  @DisplayName("候选确认前无画像，接受后按70/30累计，陈旧版本和跨用户决策被拒绝")
  void acceptsOnlyCurrentOwnedProposal() {
    Fixture first = fixture(80);
    assertThat(profiles.findByOwnerIdAndSkillIdAndCategoryKey(first.ownerId(), "java", "JAVA"))
        .isEmpty();
    var initial = commands.accept(first.ownerId(), first.proposalId(), 0);
    assertThat(initial.score()).isEqualTo(80);
    assertThat(initial.status()).isEqualTo(AbilityProfileStatus.ACTIVE);
    assertThatThrownBy(() -> commands.accept(first.ownerId(), first.proposalId(), 0))
        .isInstanceOf(BusinessException.class);

    Fixture second = fixture(first.ownerId(), 40);
    assertThatThrownBy(() -> commands.accept(UUID.randomUUID(), second.proposalId(), 0))
        .isInstanceOf(BusinessException.class);
    assertThat(commands.accept(first.ownerId(), second.proposalId(), 0).score()).isEqualTo(68);
    assertThat(proposals.findById(second.proposalId()).orElseThrow().getFinalScore())
        .isEqualTo(68);
  }

  @Test
  @DisplayName("编辑只改变确认等级与摘要，原始证据不变；拒绝不改变画像")
  void editsAndRejects() {
    Fixture first = fixture(75);
    var edited = commands.acceptWithEdit(first.ownerId(), first.proposalId(), 0,
        AbilityLevel.WEAK, "需要复习");
    assertThat(edited.level()).isEqualTo(AbilityLevel.WEAK);
    assertThat(edited.summary()).isEqualTo("需要复习");
    assertThat(evidence.findByOwnerIdAndProposalId(first.ownerId(), first.proposalId()))
        .extracting(UserAbilityEvidenceEntity::getQuestionScore).containsExactly(75);
    assertThat(proposals.findById(first.proposalId()).orElseThrow().getProposedLevel())
        .isEqualTo(AbilityLevel.PROFICIENT);

    Fixture rejected = fixture(first.ownerId(), 10);
    commands.reject(first.ownerId(), rejected.proposalId(), 0);
    assertThat(profiles.findByOwnerIdAndSkillIdAndCategoryKey(first.ownerId(), "java", "JAVA")
        .orElseThrow().getScore()).isEqualTo(75);
    assertThat(proposals.findById(rejected.proposalId()).orElseThrow().getStatus())
        .isEqualTo(AbilityProposalStatus.REJECTED);
  }

  @Test
  @DisplayName("删除来源会重放剩余已确认场次，最后一场删除后停用画像")
  void deletingSourceReplaysAcceptedEvidence() {
    Fixture first = fixture(80);
    commands.accept(first.ownerId(), first.proposalId(), 0);
    Fixture second = fixture(first.ownerId(), 40);
    commands.accept(first.ownerId(), second.proposalId(), 0);

    deletions.deleteOwnedSessions(first.ownerId(), List.of(second.sessionId()));
    var remaining = profiles.findByOwnerIdAndSkillIdAndCategoryKey(first.ownerId(), "java", "JAVA")
        .orElseThrow();
    assertThat(remaining.getScore()).isEqualTo(80);
    assertThat(remaining.getConfirmedEvidenceCount()).isEqualTo(1);
    assertThat(remaining.getStatus()).isEqualTo(AbilityProfileStatus.ACTIVE);
    assertThat(evidence.findByOwnerIdAndInterviewSessionId(first.ownerId(), second.sessionId()))
        .isEmpty();

    deletions.deleteOwnedSessions(first.ownerId(), List.of(first.sessionId()));
    assertThat(profiles.findByOwnerIdAndSkillIdAndCategoryKey(first.ownerId(), "java", "JAVA")
        .orElseThrow().getStatus()).isEqualTo(AbilityProfileStatus.DISABLED);
  }

  @Test
  @DisplayName("删除手工编辑画像的来源时不覆盖用户摘要和等级，且不能删除别人的会话")
  void deletingEditedSourceNeedsReview() {
    Fixture first = fixture(80);
    commands.accept(first.ownerId(), first.proposalId(), 0);
    Fixture second = fixture(first.ownerId(), 40);
    commands.acceptWithEdit(first.ownerId(), second.proposalId(), 0,
        AbilityLevel.STRONG, "用户说明");

    assertThatThrownBy(() -> deletions.deleteOwnedSessions(UUID.randomUUID(),
        List.of(first.sessionId()))).isInstanceOf(BusinessException.class);
    deletions.deleteOwnedSessions(first.ownerId(), List.of(first.sessionId()));
    var profile = profiles.findByOwnerIdAndSkillIdAndCategoryKey(first.ownerId(), "java", "JAVA")
        .orElseThrow();
    assertThat(profile.getStatus()).isEqualTo(AbilityProfileStatus.NEEDS_REVIEW);
    assertThat(profile.getLevel()).isEqualTo(AbilityLevel.STRONG);
    assertThat(profile.getSummary()).isEqualTo("用户说明");
  }

  @Test
  @DisplayName("已人工确认画像禁用后重新启用仍可用，来源失效的画像重新启用仍待复核")
  void enableRestoresReviewedState() {
    Fixture first = fixture(80);
    var approved = commands.acceptWithEdit(first.ownerId(), first.proposalId(), 0,
        AbilityLevel.STRONG, "用户说明");
    var disabled = commands.disable(first.ownerId(), approved.id(), approved.version());
    assertThat(commands.enable(first.ownerId(), approved.id(), disabled.version()).status())
        .isEqualTo(AbilityProfileStatus.ACTIVE);
    Fixture second = fixture(first.ownerId(), 40);
    commands.acceptWithEdit(first.ownerId(), second.proposalId(), 0,
        AbilityLevel.WEAK, "重新确认");
    deletions.deleteOwnedSessions(first.ownerId(), List.of(first.sessionId()));
    var reviewed = profiles.findByOwnerIdAndSkillIdAndCategoryKey(first.ownerId(), "java", "JAVA")
        .orElseThrow();
    assertThat(reviewed.getStatus()).isEqualTo(AbilityProfileStatus.NEEDS_REVIEW);
    var disabledAgain = commands.disable(first.ownerId(), reviewed.getId(), reviewed.getVersion());
    assertThat(commands.enable(first.ownerId(), reviewed.getId(), disabledAgain.version()).status())
        .isEqualTo(AbilityProfileStatus.NEEDS_REVIEW);
  }

  @Test
  @DisplayName("同分类两个候选并发确认只创建一份画像且都成为已确认")
  void concurrentDecisionsShareOneProfile() throws Exception {
    Fixture first = fixture(80);
    Fixture second = fixture(first.ownerId(), 40);
    var start = new CountDownLatch(1);
    var left = new AtomicReference<Throwable>();
    var right = new AtomicReference<Throwable>();
    Thread one = Thread.ofVirtual().start(() -> acceptAfter(start, first, left));
    Thread two = Thread.ofVirtual().start(() -> acceptAfter(start, second, right));
    start.countDown();
    one.join();
    two.join();
    assertThat(left.get()).isNull();
    assertThat(right.get()).isNull();
    assertThat(profiles.findByOwnerIdAndSkillIdAndCategoryKey(first.ownerId(), "java", "JAVA"))
        .hasValueSatisfying(profile -> assertThat(profile.getScore()).isIn(52, 68));
    assertThat(proposals.findById(first.proposalId()).orElseThrow().getStatus())
        .isEqualTo(AbilityProposalStatus.ACCEPTED);
    assertThat(proposals.findById(second.proposalId()).orElseThrow().getStatus())
        .isEqualTo(AbilityProposalStatus.ACCEPTED);
  }

  @Test
  @DisplayName("画像、候选与证据的查询均不能跨用户读取")
  void queryIsOwnerScoped() {
    Fixture alice = fixture(80);
    Fixture bob = fixture(60);
    commands.accept(alice.ownerId(), alice.proposalId(), 0);
    assertThat(queries.profiles(bob.ownerId(), null)).isEmpty();
    assertThatThrownBy(() -> queries.profile(bob.ownerId(), profiles
        .findByOwnerIdAndSkillIdAndCategoryKey(alice.ownerId(), "java", "JAVA")
        .orElseThrow().getId())).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> queries.proposal(bob.ownerId(), alice.proposalId()))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> queries.evidenceForProposal(bob.ownerId(), alice.proposalId()))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("删除最后一份证据后确认新候选从本场分数重新起算并重新激活")
  void acceptAfterAllEvidenceDeletedStartsFresh() {
    Fixture old = fixture(80);
    commands.accept(old.ownerId(), old.proposalId(), 0);
    deletions.deleteOwnedSessions(old.ownerId(), List.of(old.sessionId()));
    var empty = profile(old.ownerId());
    assertThat(empty.getConfirmedEvidenceCount()).isZero();
    assertThat(empty.getStatus()).isEqualTo(AbilityProfileStatus.DISABLED);

    Fixture next = fixture(old.ownerId(), 40);
    var accepted = commands.accept(old.ownerId(), next.proposalId(), 0);
    assertThat(accepted.score()).isEqualTo(40);
    assertThat(accepted.status()).isEqualTo(AbilityProfileStatus.ACTIVE);
  }

  @Test
  @DisplayName("用户主动禁用后删除全部旧证据，新确认候选仍保持禁用")
  void explicitDisableSurvivesSourceDeletionAndNewAcceptance() {
    Fixture old = fixture(80);
    var initial = commands.accept(old.ownerId(), old.proposalId(), 0);
    commands.disable(old.ownerId(), initial.id(), initial.version());
    deletions.deleteOwnedSessions(old.ownerId(), List.of(old.sessionId()));

    Fixture next = fixture(old.ownerId(), 40);
    var accepted = commands.accept(old.ownerId(), next.proposalId(), 0);
    assertThat(accepted.score()).isEqualTo(40);
    assertThat(accepted.status()).isEqualTo(AbilityProfileStatus.DISABLED);
    assertThat(commands.enable(old.ownerId(), accepted.id(), accepted.version()).status())
        .isEqualTo(AbilityProfileStatus.ACTIVE);
  }

  @Test
  @DisplayName("待复核画像主动禁用后确认新证据仍禁用，重新启用时不残留旧待复核状态")
  void newDecisionReviewsExplicitlyDisabledProfile() {
    Fixture first = fixture(80);
    commands.acceptWithEdit(first.ownerId(), first.proposalId(), 0,
        AbilityLevel.WEAK, "手工摘要");
    Fixture second = fixture(first.ownerId(), 40);
    commands.accept(second.ownerId(), second.proposalId(), 0);
    deletions.deleteOwnedSessions(second.ownerId(), List.of(second.sessionId()));
    var needsReview = profile(first.ownerId());
    assertThat(needsReview.getStatus()).isEqualTo(AbilityProfileStatus.NEEDS_REVIEW);
    commands.disable(first.ownerId(), needsReview.getId(), needsReview.getVersion());

    Fixture third = fixture(first.ownerId(), 60);
    var accepted = commands.accept(third.ownerId(), third.proposalId(), 0);
    assertThat(accepted.status()).isEqualTo(AbilityProfileStatus.DISABLED);
    assertThat(commands.enable(first.ownerId(), accepted.id(), accepted.version()).status())
        .isEqualTo(AbilityProfileStatus.ACTIVE);
  }

  @Test
  @DisplayName("删除来源后按确认顺序回放，早创建晚确认的候选不会提前计分")
  void replayUsesDecisionChronology() {
    Fixture createdFirst = fixture(80);
    Fixture createdSecond = fixture(createdFirst.ownerId(), 40);
    Fixture removed = fixture(createdFirst.ownerId(), 20);
    commands.accept(createdSecond.ownerId(), createdSecond.proposalId(), 0);
    commands.accept(createdFirst.ownerId(), createdFirst.proposalId(), 0);
    assertThat(commands.accept(removed.ownerId(), removed.proposalId(), 0).score())
        .isEqualTo(42);

    deletions.deleteOwnedSessions(removed.ownerId(), List.of(removed.sessionId()));
    assertThat(profile(createdFirst.ownerId()).getScore()).isEqualTo(52);
  }

  @Test
  @DisplayName("普通确认后删除该来源，仍在的人工批准等级摘要保持且画像待复核")
  void replayRestoresSurvivingManualDecision() {
    Fixture manual = fixture(80);
    commands.acceptWithEdit(manual.ownerId(), manual.proposalId(), 0,
        AbilityLevel.WEAK, "人工批准摘要");
    Fixture plain = fixture(manual.ownerId(), 40);
    commands.accept(plain.ownerId(), plain.proposalId(), 0);
    deletions.deleteOwnedSessions(plain.ownerId(), List.of(plain.sessionId()));

    var surviving = profile(manual.ownerId());
    assertThat(surviving.getScore()).isEqualTo(80);
    assertThat(surviving.getLevel()).isEqualTo(AbilityLevel.WEAK);
    assertThat(surviving.getSummary()).isEqualTo("人工批准摘要");
    assertThat(surviving.getStatus()).isEqualTo(AbilityProfileStatus.NEEDS_REVIEW);
    assertThat(surviving.isManuallyEdited()).isTrue();
  }

  @Test
  @DisplayName("删除更早来源时按剩余确认重算分数，同时保留最近一次人工批准的等级摘要")
  void replayUsesLatestSurvivingManualContentWhenLaterPlainDecisionSurvives() {
    Fixture removed = fixture(20);
    commands.accept(removed.ownerId(), removed.proposalId(), 0);
    Fixture manual = fixture(removed.ownerId(), 80);
    commands.acceptWithEdit(manual.ownerId(), manual.proposalId(), 0,
        AbilityLevel.STRONG, "人工批准摘要");
    Fixture plain = fixture(removed.ownerId(), 40);
    commands.accept(plain.ownerId(), plain.proposalId(), 0);

    deletions.deleteOwnedSessions(removed.ownerId(), List.of(removed.sessionId()));

    var surviving = profile(removed.ownerId());
    assertThat(surviving.getScore()).isEqualTo(68);
    assertThat(surviving.getConfirmedEvidenceCount()).isEqualTo(2);
    assertThat(surviving.getLevel()).isEqualTo(AbilityLevel.STRONG);
    assertThat(surviving.getSummary()).isEqualTo("人工批准摘要");
    assertThat(surviving.getStatus()).isEqualTo(AbilityProfileStatus.NEEDS_REVIEW);
    assertThat(surviving.isManuallyEdited()).isTrue();
  }

  @Test
  @DisplayName("同类不同场次确认与删除并发时只保留有效证据且无内部异常")
  void concurrentAcceptAndDifferentSourceDeletion() throws Exception {
    Fixture old = fixture(80);
    commands.accept(old.ownerId(), old.proposalId(), 0);
    Fixture next = fixture(old.ownerId(), 40);
    var start = new CountDownLatch(1);
    var acceptedFailure = new AtomicReference<Throwable>();
    var deletedFailure = new AtomicReference<Throwable>();
    Thread accept = Thread.ofVirtual().start(() -> acceptAfter(start, next, acceptedFailure));
    Thread delete = Thread.ofVirtual().start(() -> deleteAfter(start, old, deletedFailure));
    start.countDown();
    accept.join(Duration.ofSeconds(15));
    delete.join(Duration.ofSeconds(15));
    assertThat(accept.isAlive()).isFalse();
    assertThat(delete.isAlive()).isFalse();
    assertThat(acceptedFailure.get()).isNull();
    assertThat(deletedFailure.get()).isNull();
    assertThat(profile(old.ownerId()).getScore()).isEqualTo(40);
    assertThat(profile(old.ownerId()).getStatus()).isEqualTo(AbilityProfileStatus.ACTIVE);
  }

  @Test
  @DisplayName("同一来源删除和确认竞态仅允许业务冲突，不留下孤立候选或证据")
  void concurrentDecisionAndSameSourceDeletion() throws Exception {
    Fixture source = fixture(80);
    var start = new CountDownLatch(1);
    var acceptedFailure = new AtomicReference<Throwable>();
    var deletedFailure = new AtomicReference<Throwable>();
    Thread accept = Thread.ofVirtual().start(() -> acceptAfter(start, source, acceptedFailure));
    Thread delete = Thread.ofVirtual().start(() -> deleteAfter(start, source, deletedFailure));
    start.countDown();
    accept.join(Duration.ofSeconds(15));
    delete.join(Duration.ofSeconds(15));
    assertThat(accept.isAlive()).isFalse();
    assertThat(delete.isAlive()).isFalse();
    assertThat(deletedFailure.get()).isNull();
    if (acceptedFailure.get() != null) {
      assertThat(acceptedFailure.get()).isInstanceOf(BusinessException.class);
    }
    assertThat(proposals.findById(source.proposalId())).isEmpty();
    assertThat(evidence.findByOwnerIdAndInterviewSessionId(source.ownerId(),
        source.sessionId())).isEmpty();
  }

  private void deleteAfter(CountDownLatch start, Fixture fixture,
      AtomicReference<Throwable> failure) {
    try {
      start.await();
      deletions.deleteOwnedSessions(fixture.ownerId(), List.of(fixture.sessionId()));
    } catch (Throwable error) {
      failure.set(error);
    }
  }

  private UserAbilityProfileEntity profile(UUID ownerId) {
    return profiles.findByOwnerIdAndSkillIdAndCategoryKey(ownerId, "java", "JAVA").orElseThrow();
  }

  private void acceptAfter(CountDownLatch start, Fixture fixture,
      AtomicReference<Throwable> failure) {
    try {
      start.await();
      commands.accept(fixture.ownerId(), fixture.proposalId(), 0);
    } catch (Throwable error) {
      failure.set(error);
    }
  }

  private Fixture fixture(int score) {
    return fixture(null, score);
  }

  private Fixture fixture(UUID existingOwner, int score) {
    return transactions.execute(status -> {
      UserEntity owner;
      if (existingOwner == null) {
        String name = UUID.randomUUID().toString();
        owner = UserEntity.builder().username(name).normalizedUsername(name)
            .displayName(name).passwordHash("hash").role(UserRole.USER)
            .status(UserStatus.ACTIVE).createdAt(Instant.now()).updatedAt(Instant.now()).build();
        em.persist(owner);
      } else {
        owner = em.find(UserEntity.class, existingOwner);
      }
      var session = new InterviewSessionEntity();
      session.setOwner(owner);
      session.setSessionId(UUID.randomUUID().toString());
      em.persist(session);
      var answer = new InterviewAnswerEntity();
      answer.setSession(session);
      answer.setQuestionIndex(0);
      answer.setCategoryKey("JAVA");
      em.persist(answer);
      var proposal = new AbilityProfileProposalEntity();
      proposal.setOwner(owner);
      proposal.setInterviewSession(session);
      proposal.setSkillId("java");
      proposal.setCategoryKey("JAVA");
      proposal.setProposedLevel(new AbilityScorePolicy().levelFor(score));
      proposal.setProposedScore(score);
      proposal.setProposedSummary("建议");
      proposal.setStatus(AbilityProposalStatus.PROPOSED);
      proposal.setCreatedAt(Instant.now());
      em.persist(proposal);
      var item = new UserAbilityEvidenceEntity();
      item.setOwner(owner);
      item.setInterviewSession(session);
      item.setInterviewAnswer(answer);
      item.setProposal(proposal);
      item.setSkillId("java");
      item.setCategoryKey("JAVA");
      item.setQuestionScore(score);
      item.setOccurredAt(Instant.now());
      em.persist(item);
      em.flush();
      return new Fixture(owner.getId(), session.getId(), proposal.getId());
    });
  }

  private record Fixture(UUID ownerId, Long sessionId, Long proposalId) {
  }
}
