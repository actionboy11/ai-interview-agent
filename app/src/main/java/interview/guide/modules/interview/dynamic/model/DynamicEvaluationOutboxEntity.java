package interview.guide.modules.interview.dynamic.model;

import interview.guide.modules.auth.model.UserEntity;
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
@Table(name = "interview_dynamic_evaluation_outbox", uniqueConstraints = {
    @UniqueConstraint(name = "uk_dynamic_evaluation_version",
        columnNames = {"session_id", "frozen_version"}),
    @UniqueConstraint(name = "uk_dynamic_evaluation_message", columnNames = "message_id")
}, indexes = @Index(name = "idx_dynamic_evaluation_delivery",
    columnList = "delivery_status,next_delivery_at"))
@Check(name = "ck_dynamic_evaluation_values", constraints =
    "frozen_version >= 0 and delivery_attempts >= 0 and version >= 0")
@Getter
@Setter
@NoArgsConstructor
public class DynamicEvaluationOutboxEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "session_id", nullable = false,
      foreignKey = @ForeignKey(name = "fk_dynamic_evaluation_session"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private InterviewSessionEntity session;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "owner_user_id", nullable = false,
      foreignKey = @ForeignKey(name = "fk_dynamic_evaluation_owner"))
  private UserEntity owner;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumns(value = {
      @JoinColumn(name = "session_id", referencedColumnName = "session_id", nullable = false,
          insertable = false, updatable = false),
      @JoinColumn(name = "owner_user_id", referencedColumnName = "owner_user_id",
          nullable = false, insertable = false, updatable = false)
  }, foreignKey = @ForeignKey(name = "fk_dynamic_evaluation_runtime_scope"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  @Setter(AccessLevel.NONE)
  private DynamicInterviewRuntimeEntity runtimeScope;

  @Column(nullable = false, updatable = false)
  private long frozenVersion;

  @Column(nullable = false, updatable = false)
  private UUID messageId;

  @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
  private String frozenSnapshotJson;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private FollowUpDecisionEntity.DeliveryStatus deliveryStatus =
      FollowUpDecisionEntity.DeliveryStatus.PENDING;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant nextDeliveryAt;

  @Column(nullable = false)
  private int deliveryAttempts;

  private Instant publishedAt;
  private Instant leaseUntil;
  private UUID attemptToken;

  @Version
  private Long version;

  public DynamicEvaluationOutboxEntity(DynamicInterviewRuntimeEntity runtime, long frozenVersion,
      String frozenSnapshotJson, Instant createdAt) {
    this.session = runtime.getSession();
    this.owner = runtime.getOwner();
    this.frozenVersion = frozenVersion;
    this.messageId = UUID.randomUUID();
    this.frozenSnapshotJson = frozenSnapshotJson;
    this.createdAt = createdAt;
    this.nextDeliveryAt = createdAt;
  }
}
