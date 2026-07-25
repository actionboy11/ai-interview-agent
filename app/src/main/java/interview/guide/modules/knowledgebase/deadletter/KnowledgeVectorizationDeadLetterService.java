package interview.guide.modules.knowledgebase.deadletter;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.knowledgebase.messaging.KnowledgeVectorizationTaskPublisher;
import interview.guide.modules.knowledgebase.messaging.rabbit.KnowledgeVectorizationMessage;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import java.time.OffsetDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class KnowledgeVectorizationDeadLetterService {
    private final KnowledgeVectorizationDeadLetterRepository repository;
    private final KnowledgeBaseRepository resumeRepository;
    private final KnowledgeVectorizationTaskPublisher publisher;
    private final ObjectMapper objectMapper;
    private final TransactionalExecutor transactionalExecutor;

    public KnowledgeVectorizationDeadLetterService(
        KnowledgeVectorizationDeadLetterRepository repository,
        KnowledgeBaseRepository resumeRepository,
        KnowledgeVectorizationTaskPublisher publisher,
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
    public void record(KnowledgeVectorizationMessage message, String failureReason) {
        if (repository.existsByOriginalMessageId(message.originalMessageId().toString())) {
            return;
        }
        KnowledgeVectorizationDeadLetterEntity entity = new KnowledgeVectorizationDeadLetterEntity();
        entity.setOriginalMessageId(message.originalMessageId().toString());
        entity.setKnowledgeBaseId(message.knowledgeBaseId());
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

    public KnowledgeVectorizationDeadLetterPageResponse list(
        KnowledgeVectorizationDeadLetterStatus status,
        int page,
        int size
    ) {
        PageRequest request = PageRequest.of(
            page,
            size,
            Sort.by(Sort.Direction.DESC, "failedAt")
        );
        Page<KnowledgeVectorizationDeadLetterEntity> result =
            status == null ? repository.findAll(request) : repository.findByStatus(status, request);
        return new KnowledgeVectorizationDeadLetterPageResponse(
            result.getContent().stream().map(KnowledgeVectorizationDeadLetterDTO::from).toList(),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()
        );
    }

    public KnowledgeVectorizationDeadLetterDTO get(Long id) {
        return KnowledgeVectorizationDeadLetterDTO.from(requireDeadLetter(id));
    }

    public KnowledgeVectorizationDeadLetterDTO replay(Long id) {
        boolean claimed = transactionalExecutor.callRequiresNew(
            () -> repository.claimForReplay(id) == 1
        );
        if (!claimed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Dead letter is not pending");
        }
        KnowledgeVectorizationDeadLetterEntity deadLetter = requireDeadLetter(id);
        KnowledgeBaseEntity resume = resumeRepository.findById(deadLetter.getKnowledgeBaseId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (resume.getVectorStatus() == VectorStatus.COMPLETED) {
            deadLetter.setStatus(KnowledgeVectorizationDeadLetterStatus.RESOLVED);
            repository.save(deadLetter);
            return KnowledgeVectorizationDeadLetterDTO.from(deadLetter);
        }
        try {
            resume.setVectorStatus(VectorStatus.PENDING);
            resume.setVectorError(null);
            resumeRepository.save(resume);
            var receipt = publisher.publish(resume.getId());
            deadLetter.setReplayMessageId(receipt.messageId().toString());
            deadLetter.setReplayedAt(OffsetDateTime.now());
            deadLetter.setStatus(KnowledgeVectorizationDeadLetterStatus.REPLAYED);
            return KnowledgeVectorizationDeadLetterDTO.from(repository.save(deadLetter));
        } catch (RuntimeException exception) {
            deadLetter.setStatus(KnowledgeVectorizationDeadLetterStatus.PENDING);
            repository.save(deadLetter);
            throw exception;
        }
    }

    private KnowledgeVectorizationDeadLetterEntity requireDeadLetter(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Dead letter not found"));
    }
}
