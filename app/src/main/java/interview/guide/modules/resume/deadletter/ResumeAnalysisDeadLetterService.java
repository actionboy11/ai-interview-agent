package interview.guide.modules.resume.deadletter;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.resume.messaging.ResumeAnalysisTaskPublisher;
import interview.guide.modules.resume.messaging.rabbit.ResumeAnalysisMessage;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.time.OffsetDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ResumeAnalysisDeadLetterService {
    private final ResumeAnalysisDeadLetterRepository repository;
    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisTaskPublisher publisher;
    private final ObjectMapper objectMapper;
    private final TransactionalExecutor transactionalExecutor;

    public ResumeAnalysisDeadLetterService(
        ResumeAnalysisDeadLetterRepository repository,
        ResumeRepository resumeRepository,
        ResumeAnalysisTaskPublisher publisher,
        ObjectMapper objectMapper,
        TransactionalExecutor transactionalExecutor
    ) {
        this.repository = repository;
        this.resumeRepository = resumeRepository;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.transactionalExecutor = transactionalExecutor;
    }

    @Transactional
    public void record(ResumeAnalysisMessage message, String failureReason) {
        if (repository.existsByOriginalMessageId(message.originalMessageId().toString())) {
            return;
        }
        ResumeAnalysisDeadLetterEntity entity = new ResumeAnalysisDeadLetterEntity();
        entity.setOriginalMessageId(message.originalMessageId().toString());
        entity.setResumeId(message.resumeId());
        entity.setRetryCount(message.retryCount());
        entity.setFailureReason(failureReason);
        try {
            entity.setPayloadJson(objectMapper.writeValueAsString(message));
        } catch (Exception exception) {
            entity.setPayloadJson(null);
        }
        entity.setFailedAt(OffsetDateTime.now());
        repository.save(entity);
    }

    public ResumeAnalysisDeadLetterPageResponse list(
        ResumeAnalysisDeadLetterStatus status,
        int page,
        int size
    ) {
        PageRequest request = PageRequest.of(
            page,
            size,
            Sort.by(Sort.Direction.DESC, "failedAt")
        );
        Page<ResumeAnalysisDeadLetterEntity> result =
            status == null ? repository.findAll(request) : repository.findByStatus(status, request);
        return new ResumeAnalysisDeadLetterPageResponse(
            result.getContent().stream().map(ResumeAnalysisDeadLetterDTO::from).toList(),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()
        );
    }

    public ResumeAnalysisDeadLetterDTO get(Long id) {
        return ResumeAnalysisDeadLetterDTO.from(requireDeadLetter(id));
    }

    public ResumeAnalysisDeadLetterDTO replay(Long id) {
        boolean claimed = transactionalExecutor.callRequiresNew(
            () -> repository.claimForReplay(id) == 1
        );
        if (!claimed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Dead letter is not pending");
        }
        ResumeAnalysisDeadLetterEntity deadLetter = requireDeadLetter(id);
        ResumeEntity resume = resumeRepository.findById(deadLetter.getResumeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
        if (resume.getAnalyzeStatus() == AsyncTaskStatus.COMPLETED) {
            deadLetter.setStatus(ResumeAnalysisDeadLetterStatus.RESOLVED);
            repository.save(deadLetter);
            return ResumeAnalysisDeadLetterDTO.from(deadLetter);
        }
        try {
            resume.setAnalyzeStatus(AsyncTaskStatus.PENDING);
            resume.setAnalyzeError(null);
            resumeRepository.save(resume);
            var receipt = publisher.publish(resume.getId());
            deadLetter.setReplayMessageId(receipt.messageId().toString());
            deadLetter.setReplayedAt(OffsetDateTime.now());
            deadLetter.setStatus(ResumeAnalysisDeadLetterStatus.REPLAYED);
            return ResumeAnalysisDeadLetterDTO.from(repository.save(deadLetter));
        } catch (RuntimeException exception) {
            deadLetter.setStatus(ResumeAnalysisDeadLetterStatus.PENDING);
            repository.save(deadLetter);
            throw exception;
        }
    }

    private ResumeAnalysisDeadLetterEntity requireDeadLetter(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Dead letter not found"));
    }
}
