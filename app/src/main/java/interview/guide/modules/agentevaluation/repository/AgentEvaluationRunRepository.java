package interview.guide.modules.agentevaluation.repository;

import interview.guide.modules.agentevaluation.model.AgentEvaluationRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentEvaluationRunRepository extends JpaRepository<AgentEvaluationRunEntity, String> {
}
