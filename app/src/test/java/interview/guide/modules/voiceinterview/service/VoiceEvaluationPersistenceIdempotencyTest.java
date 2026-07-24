package interview.guide.modules.voiceinterview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class VoiceEvaluationPersistenceIdempotencyTest {
    private VoiceInterviewEvaluationRepository evaluationRepository;
    private VoiceInterviewMessageRepository messageRepository;
    private VoiceInterviewSessionRepository sessionRepository;
    private VoiceInterviewEvaluationService service;
    private VoiceInterviewSessionEntity session;

    @BeforeEach
    void setUp() {
        evaluationRepository = mock(VoiceInterviewEvaluationRepository.class);
        messageRepository = mock(VoiceInterviewMessageRepository.class);
        sessionRepository = mock(VoiceInterviewSessionRepository.class);
        TransactionalExecutor transactionalExecutor = mock(TransactionalExecutor.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(transactionalExecutor).run(any());
        service = new VoiceInterviewEvaluationService(
            mock(UnifiedEvaluationService.class),
            mock(LlmProviderRegistry.class),
            evaluationRepository,
            messageRepository,
            sessionRepository,
            new ObjectMapper(),
            mock(InterviewSkillService.class),
            transactionalExecutor
        );
        session = VoiceInterviewSessionEntity.builder().id(42L).roleType("java").build();
        when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));
    }

    @Test
    void shouldSkipAlreadyCompletedMessage() {
        when(evaluationRepository.existsByEvaluationMessageId("message-1")).thenReturn(true);

        var result = service.generateEvaluation(42L, "message-1");

        assertThat(result)
            .isEqualTo(VoiceInterviewEvaluationService.CompletionResult.ALREADY_COMPLETED);
        verify(messageRepository, never()).findBySessionIdOrderBySequenceNumAsc(any());
    }

    @Test
    void shouldSaveMessageIdAndCompleteEmptyEvaluation() {
        when(messageRepository.findBySessionIdOrderBySequenceNumAsc(42L)).thenReturn(List.of());
        when(evaluationRepository.findBySessionId(42L)).thenReturn(Optional.empty());
        when(evaluationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generateEvaluation(42L, "message-1");

        assertThat(result).isEqualTo(VoiceInterviewEvaluationService.CompletionResult.CREATED);
        var captor = org.mockito.ArgumentCaptor.forClass(VoiceInterviewEvaluationEntity.class);
        verify(evaluationRepository).save(captor.capture());
        assertThat(captor.getValue().getEvaluationMessageId()).isEqualTo("message-1");
        assertThat(session.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    }
}
