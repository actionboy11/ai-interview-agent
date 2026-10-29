package interview.guide.modules.interview.model;

import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.Check;

/**
 * 面试答案实体
 */
@Entity
@Table(name = "interview_answers",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_interview_answer_session_question", columnNames = {"session_id", "question_index"}),
        @UniqueConstraint(name = "uk_interview_answer_scope", columnNames = {"id", "session_id", "category_key"}),
        @UniqueConstraint(name = "uk_dynamic_answer_source", columnNames = {"id", "session_id", "question_index", "request_id"}),
        @UniqueConstraint(name = "uk_dynamic_answer_request", columnNames = {"submission_owner_id", "request_id"})
    },
    indexes = {
        @Index(name = "idx_interview_answer_session_question", columnList = "session_id,question_index")
    })
@Check(name = "ck_dynamic_answer_submission", constraints =
    "(submission_intent is null and request_id is null and request_fingerprint is null "
        + "and submission_owner_id is null) or (submission_intent is not null "
        + "and request_id is not null and request_fingerprint is not null "
        + "and submission_owner_id is not null)")
public class InterviewAnswerEntity {

  @Column(name = "session_id", nullable = false, insertable = false, updatable = false)
  private Long sessionDbId;

  @Enumerated(EnumType.STRING)
  @Column(name = "submission_intent", length = 20)
  private SubmissionIntent submissionIntent;

  @Column(name = "request_id")
  private UUID requestId;

  @Column(name = "request_fingerprint", length = 64)
  private String requestFingerprint;

  @Column(name = "submission_owner_id")
  // Mixed nullability with session_id is enforced by V11's composite FK, not a JPA association.
  private UUID submissionOwnerId;

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public void setRequestFingerprint(String requestFingerprint) {
    this.requestFingerprint = requestFingerprint;
  }

  public UUID getSubmissionOwnerId() {
    return submissionOwnerId;
  }

  public void setSubmissionOwnerId(UUID submissionOwnerId) {
    this.submissionOwnerId = submissionOwnerId;
  }

  public SubmissionIntent getSubmissionIntent() {
    return submissionIntent;
  }

  public void setSubmissionIntent(SubmissionIntent submissionIntent) {
    this.submissionIntent = submissionIntent;
  }

  public UUID getRequestId() {
    return requestId;
  }

  public void setRequestId(UUID requestId) {
    this.requestId = requestId;
  }
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // 关联的会话
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private InterviewSessionEntity session;
    
    // 问题索引
    @Column(name = "question_index")
    private Integer questionIndex;
    
    // 问题内容
    @Column(columnDefinition = "TEXT")
    private String question;
    
    // 机器稳定键与展示标签分开保存。
    @Column(name = "category_key", nullable = false)
    private String categoryKey = "OTHER";

    @Column(name = "category_label")
    private String categoryLabel;
    
    // 用户答案
    @Column(columnDefinition = "TEXT")
    private String userAnswer;
    
    // 得分 (0-100)
    private Integer score;
    
    // 反馈
    @Column(columnDefinition = "TEXT")
    private String feedback;
    
    // 参考答案
    @Column(columnDefinition = "TEXT")
    private String referenceAnswer;
    
    // 关键点 (JSON)
    @Column(columnDefinition = "TEXT")
    private String keyPointsJson;
    
    // 回答时间
    @Column(nullable = false)
    private LocalDateTime answeredAt;
    
    @PrePersist
    protected void onCreate() {
        answeredAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public InterviewSessionEntity getSession() {
        return session;
    }
    
    public void setSession(InterviewSessionEntity session) {
        this.session = session;
    }
    
    public Integer getQuestionIndex() {
        return questionIndex;
    }
    
    public void setQuestionIndex(Integer questionIndex) {
        this.questionIndex = questionIndex;
    }
    
    public String getQuestion() {
        return question;
    }
    
    public void setQuestion(String question) {
        this.question = question;
    }
    
    public String getCategory() {
        return categoryLabel;
    }
    
    public void setCategory(String category) {
        this.categoryLabel = category;
    }

    public String getCategoryKey() {
        return categoryKey;
    }

    public void setCategoryKey(String categoryKey) {
        this.categoryKey = categoryKey;
    }

    public String getCategoryLabel() {
        return categoryLabel;
    }

    public void setCategoryLabel(String categoryLabel) {
        this.categoryLabel = categoryLabel;
    }
    
    public String getUserAnswer() {
        return userAnswer;
    }
    
    public void setUserAnswer(String userAnswer) {
        this.userAnswer = userAnswer;
    }
    
    public Integer getScore() {
        return score;
    }
    
    public void setScore(Integer score) {
        this.score = score;
    }
    
    public String getFeedback() {
        return feedback;
    }
    
    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }
    
    public String getReferenceAnswer() {
        return referenceAnswer;
    }
    
    public void setReferenceAnswer(String referenceAnswer) {
        this.referenceAnswer = referenceAnswer;
    }
    
    public String getKeyPointsJson() {
        return keyPointsJson;
    }
    
    public void setKeyPointsJson(String keyPointsJson) {
        this.keyPointsJson = keyPointsJson;
    }
    
    public LocalDateTime getAnsweredAt() {
        return answeredAt;
    }
    
    public void setAnsweredAt(LocalDateTime answeredAt) {
        this.answeredAt = answeredAt;
    }
}
