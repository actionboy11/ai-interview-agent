package interview.guide.modules.memory.model;

import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import jakarta.persistence.Column;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "user_ability_evidence", uniqueConstraints = {
    @UniqueConstraint(name = "uk_ability_evidence_answer", columnNames = "interview_answer_id"),
    @UniqueConstraint(name = "uk_ability_evidence_scope",
        columnNames = {"id", "user_id", "interview_session_id", "category_key"})
}, indexes = {
    @Index(name = "idx_ability_evidence_owner_skill", columnList = "user_id,skill_id,category_key"),
    @Index(name = "idx_ability_evidence_session", columnList = "interview_session_id"),
    @Index(name = "idx_ability_evidence_proposal", columnList = "proposal_id")
})
@Check(constraints = "question_score between 0 and 100")
@Getter
@Setter
public class UserAbilityEvidenceEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "slot_id", updatable = false, length = 100)
  private String slotId;

  @OneToMany(mappedBy = "evidence", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("answerId ASC")
  private List<UserAbilityEvidenceSourceEntity> sources = new ArrayList<>();

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false, updatable = false)
  private UserEntity owner;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "interview_session_id", nullable = false, updatable = false)
  private InterviewSessionEntity interviewSession;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "interview_answer_id", nullable = false, updatable = false)
  private InterviewAnswerEntity interviewAnswer;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "proposal_id", nullable = false, updatable = false)
  private AbilityProfileProposalEntity proposal;

  @Column(name = "skill_id", nullable = false, updatable = false, length = 64)
  private String skillId;

  @Column(name = "category_key", nullable = false, updatable = false, length = 255)
  private String categoryKey;

  @Column(name = "topic_summary", updatable = false, columnDefinition = "text")
  private String topicSummary;

  @Column(name = "question_score", nullable = false, updatable = false)
  private int questionScore;

  @Column(updatable = false, length = 16)
  private String difficulty;

  @Column(name = "feedback_summary", updatable = false, columnDefinition = "text")
  private String feedbackSummary;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;
}
