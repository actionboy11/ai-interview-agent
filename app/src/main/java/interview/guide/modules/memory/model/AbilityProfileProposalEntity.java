package interview.guide.modules.memory.model;

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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "ability_profile_proposals", uniqueConstraints = {
    @UniqueConstraint(name = "uk_ability_proposal_session_category",
        columnNames = {"interview_session_id", "category_key"}),
    @UniqueConstraint(name = "uk_ability_proposal_scope",
        columnNames = {"id", "user_id", "interview_session_id", "skill_id", "category_key"})
}, indexes = {
    @Index(name = "idx_ability_proposal_owner_status", columnList = "user_id,status"),
    @Index(name = "idx_ability_proposal_owner_session", columnList = "user_id,interview_session_id")
})
@Check(constraints = "(previous_score is null or previous_score between 0 and 100) "
    + "and proposed_score between 0 and 100")
@Getter
@Setter
public class AbilityProfileProposalEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private UserEntity owner;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "interview_session_id", nullable = false)
  private InterviewSessionEntity interviewSession;

  @Column(name = "skill_id", nullable = false, length = 64)
  private String skillId;

  @Column(name = "category_key", nullable = false, length = 255)
  private String categoryKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "previous_level", length = 20)
  private AbilityLevel previousLevel;

  @Column(name = "previous_score")
  private Integer previousScore;

  @Enumerated(EnumType.STRING)
  @Column(name = "proposed_level", nullable = false, length = 20)
  private AbilityLevel proposedLevel;

  @Column(name = "proposed_score", nullable = false)
  private int proposedScore;

  @Column(name = "proposed_summary", columnDefinition = "text")
  private String proposedSummary;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private AbilityProposalStatus status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Version
  @Column(nullable = false)
  private Long version;
}
