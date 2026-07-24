package interview.guide.modules.voiceinterview.deadletter;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.voiceinterview.messaging.VoiceEvaluationTaskPublisher;
import interview.guide.modules.voiceinterview.messaging.rabbit.VoiceEvaluationMessage;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import java.time.OffsetDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class VoiceEvaluationDeadLetterService {
    private final VoiceEvaluationDeadLetterRepository repository;
    private final VoiceInterviewSessionRepository sessionRepository;
    private final VoiceEvaluationTaskPublisher publisher;
    private final ObjectMapper objectMapper;
    private final TransactionalExecutor transactionalExecutor;

    public VoiceEvaluationDeadLetterService(
        VoiceEvaluationDeadLetterRepository repository,
        VoiceInterviewSessionRepository sessionRepository,
        VoiceEvaluationTaskPublisher publisher,
        ObjectMapper objectMapper,
        TransactionalExecutor transactionalExecutor
    ) {
        this.repository = repository;
        this.sessionRepository = sessionRepository;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.transactionalExecutor = transactionalExecutor;
    }

    @Transactional
    public void record(VoiceEvaluationMessage message, String failureReason) {
        if (repository.existsByOriginalMessageId(message.originalMessageId().toString())) {
            return;
        }
        VoiceEvaluationDeadLetterEntity entity = new VoiceEvaluationDeadLetterEntity();
        entity.setOriginalMessageId(message.originalMessageId().toString());
        entity.setSessionId(message.sessionId());
        entity.setRetryCount(message.retryCount());
        entity.setFailureReason(failureReason);
        entity.setFailedAt(OffsetDateTime.now());
        try {
            entity.setPayloadJson(objectMapper.writeValueAsString(message));
        } catch (Exception exception) {
            entity.setPayloadJson(null);
        }
        repository.save(entity);
    }

    public VoiceEvaluationDeadLetterPageResponse list(
        VoiceEvaluationDeadLetterStatus status,
        int page,
        int size
    ) {
        PageRequest request = PageRequest.of(
            page,
            size,
            Sort.by(Sort.Direction.DESC, "failedAt")
        );
        Page<VoiceEvaluationDeadLetterEntity> result =
            status == null ? repository.findAll(request) : repository.findByStatus(status, request);
        return new VoiceEvaluationDeadLetterPageResponse(
            result.getContent().stream().map(VoiceEvaluationDeadLetterDTO::from).toList(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages()
        );
    }

    public VoiceEvaluationDeadLetterDTO get(Long id) {
        return VoiceEvaluationDeadLetterDTO.from(requireDeadLetter(id));
    }

    public VoiceEvaluationDeadLetterDTO replay(Long id) {
        boolean claimed = transactionalExecutor.callRequiresNew(
            () -> repository.claimForReplay(id) == 1
        );
        if (!claimed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Dead letter is not pending");
        }

        VoiceEvaluationDeadLetterEntity deadLetter = requireDeadLetter(id);
        VoiceInterviewSessionEntity session =
            sessionRepository.findById(deadLetter.getSessionId()).orElse(null);
        if (session == null) {
            restorePending(deadLetter);
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND);
        }
        if (session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED) {
            deadLetter.setStatus(VoiceEvaluationDeadLetterStatus.RESOLVED);
            return VoiceEvaluationDeadLetterDTO.from(repository.save(deadLetter));
        }

        try {
            session.setEvaluateStatus(AsyncTaskStatus.PENDING);
            session.setEvaluateError(null);
            sessionRepository.save(session);
            var receipt = publisher.publish(session.getId());
            deadLetter.setReplayMessageId(receipt.messageId().toString());
            deadLetter.setReplayedAt(OffsetDateTime.now());
            deadLetter.setStatus(VoiceEvaluationDeadLetterStatus.REPLAYED);
            return VoiceEvaluationDeadLetterDTO.from(repository.save(deadLetter));
        } catch (RuntimeException exception) {
            restorePending(deadLetter);
            throw exception;
        }
    }

    private void restorePending(VoiceEvaluationDeadLetterEntity deadLetter) {
        deadLetter.setStatus(VoiceEvaluationDeadLetterStatus.PENDING);
        repository.save(deadLetter);
    }

    private VoiceEvaluationDeadLetterEntity requireDeadLetter(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Dead letter not found"));
    }
}
