package interview.guide.modules.interview.deadletter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "interview_evaluation_dead_letters",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_interview_eval_dead_original_message",
        columnNames = "original_message_id"
    )
)
public class InterviewEvaluationDeadLetterEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_message_id", nullable = false, length = 36)
    private String originalMessageId;

    @Column(nullable = false)
    private String sessionId;

    @Column(nullable = false)
    private Integer retryCount;

    @Column(columnDefinition = "TEXT")
    private String failureReason;

    @Column(columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InterviewEvaluationDeadLetterStatus status =
        InterviewEvaluationDeadLetterStatus.PENDING;

    @Column(nullable = false)
    private OffsetDateTime failedAt;

    private OffsetDateTime replayedAt;

    @Column(length = 36)
    private String replayMessageId;
}

