package interview.guide.modules.interview.deadletter;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.interview.messaging.InterviewEvaluationTaskPublisher;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationMessage;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import java.time.OffsetDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class InterviewEvaluationDeadLetterService {
    private final InterviewEvaluationDeadLetterRepository repository;
    private final InterviewSessionRepository sessionRepository;
    private final InterviewEvaluationTaskPublisher publisher;
    private final ObjectMapper objectMapper;
    private final TransactionalExecutor transactionalExecutor;

    public InterviewEvaluationDeadLetterService(
        InterviewEvaluationDeadLetterRepository repository,
        InterviewSessionRepository sessionRepository,
        InterviewEvaluationTaskPublisher publisher,
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
    public void record(InterviewEvaluationMessage message, String failureReason) {
        if (repository.existsByOriginalMessageId(message.originalMessageId().toString())) {
            return;
        }
        InterviewEvaluationDeadLetterEntity entity = new InterviewEvaluationDeadLetterEntity();
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

    public InterviewEvaluationDeadLetterPageResponse list(
        InterviewEvaluationDeadLetterStatus status,
        int page,
        int size
    ) {
        PageRequest request = PageRequest.of(
            page,
            size,
            Sort.by(Sort.Direction.DESC, "failedAt")
        );
        Page<InterviewEvaluationDeadLetterEntity> result =
            status == null ? repository.findAll(request) : repository.findByStatus(status, request);
        return new InterviewEvaluationDeadLetterPageResponse(
            result.getContent().stream().map(InterviewEvaluationDeadLetterDTO::from).toList(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages()
        );
    }

    public InterviewEvaluationDeadLetterDTO get(Long id) {
        return InterviewEvaluationDeadLetterDTO.from(requireDeadLetter(id));
    }

    public InterviewEvaluationDeadLetterDTO replay(Long id) {
        boolean claimed = transactionalExecutor.callRequiresNew(
            () -> repository.claimForReplay(id) == 1
        );
        if (!claimed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Dead letter is not pending");
        }

        InterviewEvaluationDeadLetterEntity deadLetter = requireDeadLetter(id);
        InterviewSessionEntity session =
            sessionRepository.findBySessionId(deadLetter.getSessionId()).orElse(null);
        if (session == null) {
            restorePending(deadLetter);
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }
        if (session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED) {
            deadLetter.setStatus(InterviewEvaluationDeadLetterStatus.RESOLVED);
            return InterviewEvaluationDeadLetterDTO.from(repository.save(deadLetter));
        }

        try {
            session.setEvaluateStatus(AsyncTaskStatus.PENDING);
            session.setEvaluateError(null);
            sessionRepository.save(session);
            var receipt = publisher.publish(session.getSessionId());
            deadLetter.setReplayMessageId(receipt.messageId().toString());
            deadLetter.setReplayedAt(OffsetDateTime.now());
            deadLetter.setStatus(InterviewEvaluationDeadLetterStatus.REPLAYED);
            return InterviewEvaluationDeadLetterDTO.from(repository.save(deadLetter));
        } catch (RuntimeException exception) {
            restorePending(deadLetter);
            throw exception;
        }
    }

    private void restorePending(InterviewEvaluationDeadLetterEntity deadLetter) {
        deadLetter.setStatus(InterviewEvaluationDeadLetterStatus.PENDING);
        repository.save(deadLetter);
    }

    private InterviewEvaluationDeadLetterEntity requireDeadLetter(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Dead letter not found"));
    }
}

