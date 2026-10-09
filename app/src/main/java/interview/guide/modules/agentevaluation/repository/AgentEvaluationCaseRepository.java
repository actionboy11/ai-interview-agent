package interview.guide.modules.agentevaluation.repository;

import interview.guide.modules.agentevaluation.model.AgentEvaluationCaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentEvaluationCaseRepository extends JpaRepository<AgentEvaluationCaseEntity, String> {

  Optional<AgentEvaluationCaseEntity> findByRunIdAndCaseKey(String runId, String caseKey);
}
