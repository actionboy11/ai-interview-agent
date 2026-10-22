package interview.guide.modules.resume.listener;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeGradingService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Redis 简历分析准入")
class AnalyzeStreamConsumerAdmissionTest {
  @Test
  @DisplayName("账号禁用后排队任务跳过且复核阶段不调用模型")
  void disabledOwnerNeverCallsModel() {
    var resumes = mock(ResumeRepository.class);
    var grading = mock(ResumeGradingService.class);
    var consumer = new AnalyzeStreamConsumer(mock(RedisService.class), grading,
        mock(ResumePersistenceService.class), resumes);
    var resume = new ResumeEntity();
    resume.setId(42L);
    resume.setOwner(UserEntity.builder().id(UUID.randomUUID())
        .status(UserStatus.DISABLED).build());
    when(resumes.findByIdForWorker(42L)).thenReturn(Optional.of(resume));
    var payload = new AnalyzeStreamConsumer.AnalyzePayload(42L, "queued text");

    assertThat(consumer.shouldSkip(payload)).isTrue();
    consumer.processBusiness(payload);

    verifyNoInteractions(grading);
  }
}
