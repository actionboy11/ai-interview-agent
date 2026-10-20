package interview.guide.modules.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = OwnerScopedRepositoryTest.JpaTestApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:owner-repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DisplayName("根资源按所有者查询与去重")
class OwnerScopedRepositoryTest {
  @Configuration(proxyBeanMethods = false)
  @EntityScan("interview.guide.modules")
  @EnableJpaRepositories(basePackageClasses = {UserRepository.class, ResumeRepository.class,
      InterviewSessionRepository.class, VoiceInterviewSessionRepository.class,
      KnowledgeBaseRepository.class, InterviewScheduleRepository.class})
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      TransactionAutoConfiguration.class})
  static class JpaTestApplication {
  }

  @Autowired private UserRepository users;
  @Autowired private ResumeRepository resumes;
  @Autowired private InterviewSessionRepository interviews;
  @Autowired private VoiceInterviewSessionRepository voices;
  @Autowired private KnowledgeBaseRepository knowledge;
  @Autowired private RagChatSessionRepository chats;
  @Autowired private InterviewScheduleRepository schedules;
  @Autowired private TransactionTemplate transactions;

  private UserEntity first;
  private UserEntity second;

  @BeforeEach
  void createOwners() {
    first = users.saveAndFlush(user());
    second = users.saveAndFlush(user());
  }

  @Test
  @DisplayName("六类根资源的公开 ID 与列表仅命中指定所有者，会话 UUID 也隔离")
  void queriesEveryRootByOwnerAndPublicIdentifier() {
    var resume = resumes.saveAndFlush(resume(first));
    var kb = knowledge.saveAndFlush(knowledge(first));
    var interview = new InterviewSessionEntity();
    interview.setOwner(first);
    interview.setSessionId(UUID.randomUUID().toString());
    interview.setResume(resume);
    interview = interviews.saveAndFlush(interview);
    var voice = VoiceInterviewSessionEntity.builder().owner(first).roleType("Java").build();
    voice = voices.saveAndFlush(voice);
    var chat = new RagChatSessionEntity();
    chat.setOwner(first);
    chat.setTitle("Knowledge chat");
    chat.getKnowledgeBases().add(kb);
    chat = chats.saveAndFlush(chat);
    var schedule = new InterviewScheduleEntity();
    schedule.setOwner(first);
    schedule.setCompanyName("Company");
    schedule.setPosition("Engineer");
    schedule.setInterviewTime(LocalDateTime.of(2026, 10, 20, 10, 0));
    schedule = schedules.saveAndFlush(schedule);

    assertThat(resumes.findByOwnerIdAndId(first.getId(), resume.getId())).isPresent();
    assertThat(resumes.findByOwnerIdAndId(second.getId(), resume.getId())).isEmpty();
    assertThat(knowledge.findByOwnerIdAndId(first.getId(), kb.getId())).isPresent();
    assertThat(knowledge.findByOwnerIdAndId(second.getId(), kb.getId())).isEmpty();
    assertThat(interviews.findByOwnerIdAndId(first.getId(), interview.getId())).isPresent();
    assertThat(interviews.findByOwnerIdAndId(second.getId(), interview.getId())).isEmpty();
    assertThat(interviews.findByOwnerIdAndSessionId(first.getId(), interview.getSessionId()))
        .isPresent();
    assertThat(interviews.findByOwnerIdAndSessionId(second.getId(), interview.getSessionId()))
        .isEmpty();
    assertThat(voices.findByOwnerIdAndId(first.getId(), voice.getId())).isPresent();
    assertThat(voices.findByOwnerIdAndId(second.getId(), voice.getId())).isEmpty();
    assertThat(chats.findByOwnerIdAndId(first.getId(), chat.getId())).isPresent();
    assertThat(chats.findByOwnerIdAndId(second.getId(), chat.getId())).isEmpty();
    assertThat(schedules.findByOwnerIdAndId(first.getId(), schedule.getId())).isPresent();
    assertThat(schedules.findByOwnerIdAndId(second.getId(), schedule.getId())).isEmpty();

    assertThat(resumes.findByOwnerIdOrderByUploadedAtDesc(first.getId())).hasSize(1);
    assertThat(resumes.findByOwnerIdOrderByUploadedAtDesc(second.getId())).isEmpty();
    assertThat(knowledge.findByOwnerIdOrderByUploadedAtDesc(first.getId())).hasSize(1);
    assertThat(knowledge.findByOwnerIdOrderByUploadedAtDesc(second.getId())).isEmpty();
    assertThat(interviews.findByOwnerIdOrderByCreatedAtDesc(first.getId())).hasSize(1);
    assertThat(interviews.findByOwnerIdOrderByCreatedAtDesc(second.getId())).isEmpty();
    assertThat(voices.findByOwnerIdOrderByUpdatedAtDesc(first.getId())).hasSize(1);
    assertThat(voices.findByOwnerIdOrderByUpdatedAtDesc(second.getId())).isEmpty();
    assertThat(chats.findByOwnerIdOrderByIsPinnedDescUpdatedAtDesc(first.getId())).hasSize(1);
    assertThat(chats.findByOwnerIdOrderByIsPinnedDescUpdatedAtDesc(second.getId())).isEmpty();
    assertThat(schedules.findByOwnerIdOrderByInterviewTimeAsc(first.getId())).hasSize(1);
    assertThat(schedules.findByOwnerIdOrderByInterviewTimeAsc(second.getId())).isEmpty();

    Long resumeId = resume.getId();
    transactions.executeWithoutResult(status -> {
      var loaded = resumes.findByOwnerIdAndId(first.getId(), resumeId).orElseThrow();
      assertThat(Hibernate.isInitialized(loaded.getOwner())).isFalse();
      assertThat(loaded.getOwner().getId()).isEqualTo(first.getId());
    });
  }

  @Test
  @DisplayName("简历与知识库哈希跨用户允许重复，哈希查找与存在检查按用户限定")
  void permitsEqualHashesAcrossOwners() {
    var firstResume = resumes.saveAndFlush(resume(first));
    var secondResume = resumes.saveAndFlush(resume(second));
    var firstKnowledge = knowledge.saveAndFlush(knowledge(first));
    var secondKnowledge = knowledge.saveAndFlush(knowledge(second));
    assertThat(resumes.findByOwnerIdAndFileHash(first.getId(), "same-hash"))
        .get().extracting(ResumeEntity::getId).isEqualTo(firstResume.getId());
    assertThat(resumes.findByOwnerIdAndFileHash(second.getId(), "same-hash"))
        .get().extracting(ResumeEntity::getId).isEqualTo(secondResume.getId());
    assertThat(knowledge.findByOwnerIdAndFileHash(first.getId(), "same-hash"))
        .get().extracting(KnowledgeBaseEntity::getId).isEqualTo(firstKnowledge.getId());
    assertThat(knowledge.findByOwnerIdAndFileHash(second.getId(), "same-hash"))
        .get().extracting(KnowledgeBaseEntity::getId).isEqualTo(secondKnowledge.getId());
    assertThat(resumes.existsByOwnerIdAndFileHash(first.getId(), "same-hash")).isTrue();
    assertThat(knowledge.existsByOwnerIdAndFileHash(first.getId(), "same-hash")).isTrue();
    assertThat(resumes.existsByOwnerIdAndFileHash(UUID.randomUUID(), "same-hash")).isFalse();
    assertThat(knowledge.existsByOwnerIdAndFileHash(UUID.randomUUID(), "same-hash")).isFalse();
  }

  @Test
  @DisplayName("同一用户重复简历哈希被数据库拒绝")
  void rejectsDuplicateResumeHashWithinOwner() {
    resumes.saveAndFlush(resume(first));
    assertThatThrownBy(() -> resumes.saveAndFlush(resume(first)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("同一用户重复知识库哈希被数据库拒绝")
  void rejectsDuplicateKnowledgeHashWithinOwner() {
    knowledge.saveAndFlush(knowledge(first));
    assertThatThrownBy(() -> knowledge.saveAndFlush(knowledge(first)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private UserEntity user() {
    String name = UUID.randomUUID().toString();
    return UserEntity.builder().username(name).normalizedUsername(name).displayName(name)
        .passwordHash("hash").role(UserRole.USER).status(UserStatus.ACTIVE)
        .createdAt(Instant.now()).updatedAt(Instant.now()).build();
  }

  private ResumeEntity resume(UserEntity owner) {
    var resume = new ResumeEntity();
    resume.setOwner(owner);
    resume.setFileHash("same-hash");
    resume.setOriginalFilename("resume.pdf");
    return resume;
  }

  private KnowledgeBaseEntity knowledge(UserEntity owner) {
    var kb = new KnowledgeBaseEntity();
    kb.setOwner(owner);
    kb.setFileHash("same-hash");
    kb.setName("Java");
    kb.setOriginalFilename("java.pdf");
    return kb;
  }
}
