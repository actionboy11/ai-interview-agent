package interview.guide.modules.memory.model;

import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** A supporting formal answer shares the evidence's owner, session and category. */
@Entity
@Table(name = "user_ability_evidence_sources", uniqueConstraints = @UniqueConstraint(
    name = "uk_ability_evidence_source", columnNames = {"evidence_id", "answer_id"}),
    indexes = @Index(name = "idx_ability_source_answer", columnList = "answer_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAbilityEvidenceSourceEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumns(value = {
      @JoinColumn(name = "evidence_id", referencedColumnName = "id", nullable = false),
      @JoinColumn(name = "user_id", referencedColumnName = "user_id", nullable = false),
      @JoinColumn(name = "interview_session_id", referencedColumnName = "interview_session_id",
          nullable = false),
      @JoinColumn(name = "category_key", referencedColumnName = "category_key", nullable = false)
  }, foreignKey = @ForeignKey(name = "fk_ability_source_evidence_scope"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private UserAbilityEvidenceEntity evidence;

  @Column(name = "answer_id", nullable = false, updatable = false)
  private Long answerId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumns(value = {
      @JoinColumn(name = "answer_id", referencedColumnName = "id", nullable = false,
          insertable = false, updatable = false),
      @JoinColumn(name = "interview_session_id", referencedColumnName = "session_id",
          nullable = false, insertable = false, updatable = false),
      @JoinColumn(name = "category_key", referencedColumnName = "category_key", nullable = false,
          insertable = false, updatable = false)
  }, foreignKey = @ForeignKey(name = "fk_ability_source_answer_scope"))
  private InterviewAnswerEntity answerScope;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumns(value = {
      @JoinColumn(name = "interview_session_id", referencedColumnName = "id", nullable = false,
          insertable = false, updatable = false),
      @JoinColumn(name = "user_id", referencedColumnName = "owner_user_id", nullable = false,
          insertable = false, updatable = false)
  }, foreignKey = @ForeignKey(name = "fk_ability_source_session_owner"))
  private InterviewSessionEntity ownedSession;

  public UserAbilityEvidenceSourceEntity(UserAbilityEvidenceEntity evidence, Long answerId) {
    this.evidence = evidence;
    this.answerId = answerId;
  }
}
