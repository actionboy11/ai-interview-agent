package interview.guide.modules.voiceinterview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.messaging.VoiceEvaluationTaskPublisher;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

class VoiceInterviewWebSocketUrlTest {

  @Test
  @DisplayName("会话响应返回与部署环境无关的 WebSocket 相对路径")
  void shouldReturnDeploymentNeutralWebSocketPath() {
    VoiceInterviewSessionRepository sessionRepository = mock(VoiceInterviewSessionRepository.class);
    VoiceInterviewMessageRepository messageRepository = mock(VoiceInterviewMessageRepository.class);
    RedissonClient redissonClient = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    RBucket<VoiceInterviewSessionEntity> bucket = mock(RBucket.class);
    VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
        .id(42L)
        .roleType("java-backend")
        .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
        .status(VoiceInterviewSessionStatus.PAUSED)
        .build();
    when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));
    when(sessionRepository.save(session)).thenReturn(session);
    when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(bucket);

    VoiceInterviewService service = new VoiceInterviewService(
        sessionRepository,
        messageRepository,
        mock(VoiceInterviewEvaluationRepository.class),
        redissonClient,
        mock(VoiceInterviewProperties.class),
        mock(VoiceEvaluationTaskPublisher.class),
        mock(LlmProviderRegistry.class)
    );

    SessionResponseDTO response = service.resumeSession("42");

    assertThat(response.getWebSocketUrl()).isEqualTo("/ws/voice-interview/42");
    assertThat(response.getWebSocketUrl()).doesNotContain("localhost");
  }
}
