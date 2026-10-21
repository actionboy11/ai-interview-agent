package interview.guide.modules.memory.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.memory.model.AbilityLevel;
import interview.guide.modules.memory.model.AbilityProfileProposalEntity;
import interview.guide.modules.memory.model.AbilityProfileStatus;
import interview.guide.modules.memory.model.AbilityProposalStatus;
import interview.guide.modules.memory.model.UserAbilityEvidenceEntity;
import interview.guide.modules.memory.model.UserAbilityProfileEntity;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.Hibernate;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = AbilityMemoryRepositoryTest.JpaTestApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:ability-memory;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DisplayName("能力画像、候选与证据持久化")
class AbilityMemoryRepositoryTest {
  @Configuration(proxyBeanMethods = false)
  @EntityScan("interview.guide.modules")
  @EnableJpaRepositories(basePackageClasses = UserAbilityProfileRepository.class)
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      TransactionAutoConfiguration.class})
  static class JpaTestApplication {
  }

  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactions;
  @Autowired private UserAbilityProfileRepository profiles;
  @Autowired private AbilityProfileProposalRepository proposals;
  @Autowired private UserAbilityEvidenceRepository evidence;

  @Test
  @DisplayName("画像按用户技能分类唯一且只有 ACTIVE 查询可供规划")
  void scopesProfilesAndActiveLookup() {
    var first = user();
    var second = user();
    var active = profiles.saveAndFlush(profile(first, "JAVA", AbilityProfileStatus.ACTIVE));
    profiles.saveAndFlush(profile(first, "SPRING", AbilityProfileStatus.NEEDS_REVIEW));
    profiles.saveAndFlush(profile(second, "JAVA", AbilityProfileStatus.ACTIVE));

    assertThat(profiles.findByOwnerIdAndSkillIdAndStatus(first.getId(), "java-backend",
        AbilityProfileStatus.ACTIVE)).extracting(UserAbilityProfileEntity::getId)
        .containsExactly(active.getId());
    assertThat(profiles.findByOwnerIdAndSkillIdAndStatus(first.getId(), "java-backend",
        AbilityProfileStatus.DISABLED)).isEmpty();
    assertThat(profiles.findByOwnerIdAndSkillIdAndCategoryKey(first.getId(),
        "java-backend", "JAVA")).isPresent();
    assertThat(profiles.findByOwnerIdAndSkillIdAndCategoryKey(second.getId(),
        "java-backend", "SPRING")).isEmpty();
    assertThatThrownBy(() -> profiles.saveAndFlush(profile(first, "JAVA",
        AbilityProfileStatus.DISABLED))).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("证据保留本场候选关联且重复答案或同场分类候选被拒绝")
  void linksEvidenceAndEnforcesIdempotency() {
    var owner = user();
    var session = session(owner);
    var answer = answer(session, 0);
    var proposal = proposals.saveAndFlush(proposal(owner, session, "JAVA"));
    var saved = evidence.saveAndFlush(evidence(owner, session, answer, proposal));

    assertThat(evidence.findByOwnerIdAndProposalId(owner.getId(), proposal.getId()))
        .extracting(UserAbilityEvidenceEntity::getId).containsExactly(saved.getId());
    assertThat(evidence.findByOwnerIdAndInterviewAnswerId(owner.getId(), answer.getId()))
        .hasValueSatisfying(found -> assertThat(found.getProposal().getId())
            .isEqualTo(proposal.getId()));
    assertThat(proposals.findByOwnerIdAndInterviewSessionIdAndCategoryKey(owner.getId(),
        session.getId(), "JAVA")).isPresent();
    assertThat(proposals.findByOwnerIdAndStatus(owner.getId(), AbilityProposalStatus.PROPOSED))
        .extracting(AbilityProfileProposalEntity::getId).contains(proposal.getId());
    assertThatThrownBy(() -> evidence.saveAndFlush(evidence(owner, session, answer, proposal)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> proposals.saveAndFlush(proposal(owner, session, "JAVA")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("画像与候选版本阻止陈旧更新覆盖新决策")
  void rejectsStaleUpdates() {
    var owner = user();
    var session = session(owner);
    var savedProfile = profiles.saveAndFlush(profile(owner, "JAVA", AbilityProfileStatus.ACTIVE));
    var staleProfile = profiles.findById(savedProfile.getId()).orElseThrow();
    savedProfile.setSummary("已更新");
    profiles.saveAndFlush(savedProfile);
    staleProfile.setSummary("旧决定");
    assertThatThrownBy(() -> profiles.saveAndFlush(staleProfile))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    var savedProposal = proposals.saveAndFlush(proposal(owner, session, "JAVA"));
    var staleProposal = proposals.findById(savedProposal.getId()).orElseThrow();
    savedProposal.setStatus(AbilityProposalStatus.ACCEPTED);
    proposals.saveAndFlush(savedProposal);
    staleProposal.setStatus(AbilityProposalStatus.REJECTED);
    assertThatThrownBy(() -> proposals.saveAndFlush(staleProposal))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  @DisplayName("UUID 所有者通过懒加载外键保存，跨用户无法按所有者查询")
  void usesLazyOwnerAndScopedQueries() {
    var owner = user();
    var stranger = user();
    var saved = profiles.saveAndFlush(profile(owner, "JAVA", AbilityProfileStatus.ACTIVE));
    assertThat(profiles.findByOwnerIdAndId(stranger.getId(), saved.getId())).isEmpty();
    transactions.executeWithoutResult(status -> {
      var found = profiles.findByOwnerIdAndId(owner.getId(), saved.getId()).orElseThrow();
      assertThat(Hibernate.isInitialized(found.getOwner())).isFalse();
      assertThat(found.getOwner().getId()).isEqualTo(owner.getId());
    });
  }

  private UserEntity user() {
    String name = UUID.randomUUID().toString();
    return transactions.execute(status -> {
      var user = UserEntity.builder().username(name).normalizedUsername(name)
          .displayName(name).passwordHash("hash").role(UserRole.USER)
          .status(UserStatus.ACTIVE).createdAt(Instant.now()).updatedAt(Instant.now()).build();
      entityManager.persist(user);
      return user;
    });
  }

  private InterviewSessionEntity session(UserEntity owner) {
    return transactions.execute(status -> {
      var session = new InterviewSessionEntity();
      session.setOwner(owner);
      session.setSessionId(UUID.randomUUID().toString());
      entityManager.persist(session);
      return session;
    });
  }

  private InterviewAnswerEntity answer(InterviewSessionEntity session, int questionIndex) {
    return transactions.execute(status -> {
      var answer = new InterviewAnswerEntity();
      answer.setSession(session);
      answer.setQuestionIndex(questionIndex);
      answer.setCategoryKey("JAVA");
      entityManager.persist(answer);
      return answer;
    });
  }

  private UserAbilityProfileEntity profile(UserEntity owner, String category,
      AbilityProfileStatus status) {
    var profile = new UserAbilityProfileEntity();
    profile.setOwner(owner);
    profile.setSkillId("java-backend");
    profile.setCategoryKey(category);
    profile.setLevel(AbilityLevel.COMPETENT);
    profile.setScore(70);
    profile.setConfidence(0.5);
    profile.setSummary("摘要");
    profile.setConfirmedEvidenceCount(1);
    profile.setStatus(status);
    profile.setLastEvaluatedAt(Instant.now());
    profile.setLastConfirmedAt(Instant.now());
    return profile;
  }

  private AbilityProfileProposalEntity proposal(UserEntity owner,
      InterviewSessionEntity session, String category) {
    var proposal = new AbilityProfileProposalEntity();
    proposal.setOwner(owner);
    proposal.setInterviewSession(session);
    proposal.setSkillId("java-backend");
    proposal.setCategoryKey(category);
    proposal.setPreviousLevel(AbilityLevel.DEVELOPING);
    proposal.setPreviousScore(55);
    proposal.setProposedLevel(AbilityLevel.COMPETENT);
    proposal.setProposedScore(70);
    proposal.setProposedSummary("建议摘要");
    proposal.setStatus(AbilityProposalStatus.PROPOSED);
    proposal.setCreatedAt(Instant.now());
    return proposal;
  }

  private UserAbilityEvidenceEntity evidence(UserEntity owner,
      InterviewSessionEntity session, InterviewAnswerEntity answer,
      AbilityProfileProposalEntity proposal) {
    var item = new UserAbilityEvidenceEntity();
    item.setOwner(owner);
    item.setInterviewSession(session);
    item.setInterviewAnswer(answer);
    item.setProposal(proposal);
    item.setSkillId("java-backend");
    item.setCategoryKey("JAVA");
    item.setTopicSummary("集合原理");
    item.setQuestionScore(70);
    item.setDifficulty("mid");
    item.setFeedbackSummary("能解释基础概念");
    item.setOccurredAt(Instant.now());
    return item;
  }
}
