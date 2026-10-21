package interview.guide.modules.interview.planning.model;

import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "interview_question_plans", indexes =
    @Index(name = "idx_question_plan_owner_session", columnList = "owner_user_id,session_id"))
@Getter
@Setter
public class InterviewQuestionPlanEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "session_id", nullable = false)
  private InterviewSessionEntity session;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "owner_user_id", nullable = false)
  private UserEntity owner;

  @Column(name = "plan_version", nullable = false)
  private int planVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "planning_mode", nullable = false, length = 20)
  private PlanningMode planningMode;

  @Column(name = "fallback_reason", length = 100)
  private String fallbackReason;

  @Column(name = "plan_json", nullable = false, columnDefinition = "TEXT")
  private String planJson;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();
}
