package interview.guide.modules.memory.model;

import interview.guide.modules.auth.model.UserEntity;
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
@Table(name = "user_ability_profiles", uniqueConstraints = {
    @UniqueConstraint(name = "uk_ability_profile_owner_skill_category",
        columnNames = {"user_id", "skill_id", "category_key"})
}, indexes = {
    @Index(name = "idx_ability_profile_owner_skill_status",
        columnList = "user_id,skill_id,status")
})
@Check(constraints = "score between 0 and 100 and confidence between 0 and 1 "
    + "and confirmed_evidence_count >= 0")
@Getter
@Setter
public class UserAbilityProfileEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private UserEntity owner;

  @Column(name = "skill_id", nullable = false, length = 64)
  private String skillId;

  @Column(name = "category_key", nullable = false, length = 255)
  private String categoryKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private AbilityLevel level;

  @Column(nullable = false)
  private int score;

  @Column(nullable = false)
  private double confidence;

  @Column(columnDefinition = "text")
  private String summary;

  @Column(name = "confirmed_evidence_count", nullable = false)
  private int confirmedEvidenceCount;

  @Column(name = "manually_edited", nullable = false)
  private boolean manuallyEdited;

  @Enumerated(EnumType.STRING)
  @Column(name = "status_before_disable", length = 20)
  private AbilityProfileStatus statusBeforeDisable;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private AbilityProfileStatus status;

  @Column(name = "last_evaluated_at")
  private Instant lastEvaluatedAt;

  @Column(name = "last_confirmed_at")
  private Instant lastConfirmedAt;

  @Version
  @Column(nullable = false)
  private Long version;
}
