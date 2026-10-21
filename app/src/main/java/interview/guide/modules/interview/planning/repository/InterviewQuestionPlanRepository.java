package interview.guide.modules.interview.planning.repository;

import interview.guide.modules.interview.planning.model.InterviewQuestionPlanEntity;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewQuestionPlanRepository extends JpaRepository<InterviewQuestionPlanEntity, Long> {
  List<InterviewQuestionPlanEntity> findByOwnerIdAndSessionSessionId(UUID ownerId,
      String sessionId);

  Optional<InterviewQuestionPlanEntity> findFirstByOwnerIdAndSessionSessionIdOrderByIdDesc(UUID ownerId,
      String sessionId);
}
