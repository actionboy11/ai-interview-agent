package interview.guide.modules.interview.listener;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.MemoryDerivationPublicationService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Redis 文字评估准入")
class EvaluateStreamConsumerAdmissionTest {
  @Test
  @DisplayName("账号禁用后排队任务跳过且复核阶段不调用模型")
  void disabledOwnerNeverCallsModel() {
    var sessions = mock(InterviewSessionRepository.class);
    var evaluation = mock(AnswerEvaluationService.class);
    var consumer = new EvaluateStreamConsumer(mock(RedisService.class), sessions,
        evaluation, mock(InterviewPersistenceService.class), new ObjectMapper(),
        mock(LlmProviderRegistry.class), mock(InterviewSessionCache.class),
        mock(MemoryDerivationPublicationService.class));
    var session = new InterviewSessionEntity();
    session.setSessionId("text-42");
    session.setQuestionsJson("[]");
    session.setOwner(UserEntity.builder().id(UUID.randomUUID())
        .status(UserStatus.DISABLED).build());
    when(sessions.findBySessionIdWithResume("text-42")).thenReturn(Optional.of(session));
    var payload = new EvaluateStreamConsumer.EvaluatePayload("text-42");

    assertThat(consumer.shouldSkip(payload)).isTrue();
    consumer.processBusiness(payload);

    verifyNoInteractions(evaluation);
  }
}
