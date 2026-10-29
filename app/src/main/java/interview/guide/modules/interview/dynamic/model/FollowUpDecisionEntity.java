package interview.guide.modules.interview.dynamic.model;

import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "interview_follow_up_decisions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_follow_up_source_answer", columnNames = "source_answer_id"),
    @UniqueConstraint(name = "uk_follow_up_owner_request",
        columnNames = {"owner_user_id", "request_id"})
}, indexes = {
    @Index(name = "idx_follow_up_delivery", columnList = "delivery_status,next_delivery_at"),
    @Index(name = "idx_follow_up_recovery", columnList = "status,deadline")
})
@Check(name = "ck_follow_up_values", constraints =
    "source_question_index >= 0 and expected_runtime_version >= 0 and version >= 0 "
        + "and delivery_attempts >= 0 and deadline > created_at "
        + "and (applied_runtime_version is null or applied_runtime_version >= 0)")
@Getter
@Setter
@NoArgsConstructor
public class FollowUpDecisionEntity {
  public enum Status { PENDING, PROCESSING, APPLIED, FAILED, CANCELLED }
  public enum DeliveryStatus { PENDING, PUBLISHED }
  public enum Action { FOLLOW_UP, NEXT_MAIN }
  public enum Reason { IMPORTANT_GAP, SUFFICIENT, NO_USEFUL_FOLLOW_UP, USER_DONT_KNOW }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "session_id", nullable = false,
      foreignKey = @ForeignKey(name = "fk_follow_up_session"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private InterviewSessionEntity session;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "owner_user_id", nullable = false,
      foreignKey = @ForeignKey(name = "fk_follow_up_owner"))
  private UserEntity owner;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumns(value = {
      @JoinColumn(name = "session_id", referencedColumnName = "session_id", nullable = false,
          insertable = false, updatable = false),
      @JoinColumn(name = "owner_user_id", referencedColumnName = "owner_user_id",
          nullable = false, insertable = false, updatable = false)
  }, foreignKey = @ForeignKey(name = "fk_follow_up_runtime_scope"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  @Setter(AccessLevel.NONE)
  private DynamicInterviewRuntimeEntity runtimeScope;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "source_answer_id", nullable = false,
      foreignKey = @ForeignKey(name = "fk_follow_up_source_answer"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private InterviewAnswerEntity sourceAnswer;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumns(value = {
      @JoinColumn(name = "source_answer_id", referencedColumnName = "id", nullable = false,
          insertable = false, updatable = false),
      @JoinColumn(name = "session_id", referencedColumnName = "session_id", nullable = false,
          insertable = false, updatable = false),
      @JoinColumn(name = "source_question_index", referencedColumnName = "question_index",
          nullable = false, insertable = false, updatable = false),
      @JoinColumn(name = "request_id", referencedColumnName = "request_id", nullable = false,
          insertable = false, updatable = false)
  }, foreignKey = @ForeignKey(name = "fk_follow_up_answer_scope"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  @Setter(AccessLevel.NONE)
  private InterviewAnswerEntity sourceScope;

  @Column(name = "source_question_index", nullable = false, updatable = false)
  private int sourceQuestionIndex;

  @Column(nullable = false, length = 100, updatable = false)
  private String slotId;

  @Column(name = "request_id", nullable = false, updatable = false)
  private UUID requestId;

  @Column(nullable = false, length = 64, updatable = false)
  private String requestFingerprint;

  @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
  private String contextJson;

  @Column(nullable = false, updatable = false)
  private Instant deadline;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false, updatable = false)
  private long expectedRuntimeVersion;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Status status = Status.PENDING;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private DeliveryStatus deliveryStatus = DeliveryStatus.PENDING;

  @Column(nullable = false)
  private Instant nextDeliveryAt;

  @Column(nullable = false)
  private int deliveryAttempts;

  private Instant publishedAt;
  private Instant leaseUntil;
  private UUID attemptToken;

  @Enumerated(EnumType.STRING)
  @Column(length = 20)
  private Action modelAction;

  @Enumerated(EnumType.STRING)
  @Column(length = 24)
  private Reason modelReason;

  @Column(length = 500)
  private String reasonSummary;

  @Column(columnDefinition = "TEXT")
  private String modelDecisionJson;

  @Enumerated(EnumType.STRING)
  @Column(length = 20)
  private Action finalAction;

  @Enumerated(EnumType.STRING)
  @Column(length = 24)
  private DynamicGroupEndReason endReason;

  @Column(length = 64)
  private String errorCode;

  private Long appliedRuntimeVersion;

  @Version
  private Long version;

  public FollowUpDecisionEntity(DynamicInterviewRuntimeEntity runtime,
      InterviewAnswerEntity answer, String contextJson, Instant createdAt, Instant deadline) {
    this.session = runtime.getSession();
    this.owner = runtime.getOwner();
    this.sourceAnswer = answer;
    this.sourceQuestionIndex = answer.getQuestionIndex();
    this.slotId = runtime.getCurrentSlotId();
    this.requestId = answer.getRequestId();
    this.requestFingerprint = answer.getRequestFingerprint();
    this.contextJson = contextJson;
    this.createdAt = createdAt;
    this.deadline = deadline;
    this.expectedRuntimeVersion = runtime.getVersion();
    this.nextDeliveryAt = createdAt;
  }
}
