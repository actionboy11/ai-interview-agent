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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "interview_dynamic_runtime", uniqueConstraints = {
    @UniqueConstraint(name = "uk_dynamic_runtime_session", columnNames = "session_id"),
    @UniqueConstraint(name = "uk_dynamic_runtime_scope",
        columnNames = {"session_id", "owner_user_id"})
}, indexes = @Index(name = "idx_dynamic_runtime_phase", columnList = "phase"))
@Check(name = "ck_dynamic_runtime_values", constraints =
    "execution_version = 1 and planned_main_question_count > 0 "
        + "and max_follow_ups_per_group = 2 "
        + "and total_follow_up_budget = planned_main_question_count "
        + "and decision_timeout_seconds > 0 and current_main_ordinal >= 0 "
        + "and current_main_ordinal < planned_main_question_count "
        + "and current_question_index >= 0 and group_used_follow_ups between 0 and 2 "
        + "and used_follow_ups between 0 and total_follow_up_budget "
        + "and group_used_follow_ups <= used_follow_ups "
        + "and completed_main_count between 0 and planned_main_question_count "
        + "and version >= 0 and (frozen_version is null or frozen_version >= 0)")
@Getter
@Setter
@NoArgsConstructor
public class DynamicInterviewRuntimeEntity {
  @Column(name = "session_id", nullable = false, insertable = false, updatable = false)
  @Setter(AccessLevel.NONE)
  private Long sessionDbId;

  @Column(name = "owner_user_id", nullable = false, insertable = false, updatable = false)
  @Setter(AccessLevel.NONE)
  private UUID ownerUserId;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "session_id", nullable = false,
      foreignKey = @ForeignKey(name = "fk_dynamic_runtime_session"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private InterviewSessionEntity session;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "owner_user_id", nullable = false,
      foreignKey = @ForeignKey(name = "fk_dynamic_runtime_owner"))
  private UserEntity owner;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumns(value = {
      @JoinColumn(name = "session_id", referencedColumnName = "id", nullable = false,
          insertable = false, updatable = false),
      @JoinColumn(name = "owner_user_id", referencedColumnName = "owner_user_id",
          nullable = false, insertable = false, updatable = false)
  }, foreignKey = @ForeignKey(name = "fk_dynamic_runtime_session_owner"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  @Setter(AccessLevel.NONE)
  private InterviewSessionEntity ownedSession;

  @Version
  private Long version;

  @Column(nullable = false, updatable = false)
  @Setter(AccessLevel.NONE)
  private int executionVersion = 1;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private DynamicPhase phase = DynamicPhase.WAITING_ANSWER;

  @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
  @Setter(AccessLevel.NONE)
  private String mainQuestionsJson;

  @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
  @Setter(AccessLevel.NONE)
  private String policyJson;

  @Column(nullable = false, updatable = false)
  @Setter(AccessLevel.NONE)
  private int plannedMainQuestionCount;

  @Column(nullable = false, updatable = false)
  @Setter(AccessLevel.NONE)
  private int maxFollowUpsPerGroup = 2;

  @Column(nullable = false, updatable = false)
  @Setter(AccessLevel.NONE)
  private int totalFollowUpBudget;

  @Column(nullable = false, updatable = false)
  @Setter(AccessLevel.NONE)
  private int decisionTimeoutSeconds = 30;

  @Column(nullable = false, length = 100)
  private String currentSlotId;

  @Column(nullable = false)
  private int currentMainOrdinal;

  @Column(nullable = false)
  private int currentQuestionIndex;

  @Column(nullable = false)
  private int groupUsedFollowUps;

  @Column(nullable = false)
  private int usedFollowUps;

  @Column(nullable = false)
  private int completedMainCount;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String groupEndReasonsJson = "{\"schemaVersion\":1,\"groups\":{}}";

  private UUID pendingDecisionId;
  private Long frozenVersion;

  @Column(length = 64)
  private String noticeCode;

  public DynamicInterviewRuntimeEntity(InterviewSessionEntity session, String mainQuestionsJson,
      String policyJson, int plannedMainQuestionCount, String firstSlotId) {
    this.session = session;
    this.owner = session.getOwner();
    this.mainQuestionsJson = mainQuestionsJson;
    this.policyJson = policyJson;
    this.plannedMainQuestionCount = plannedMainQuestionCount;
    this.totalFollowUpBudget = plannedMainQuestionCount;
    this.currentSlotId = firstSlotId;
  }
}
