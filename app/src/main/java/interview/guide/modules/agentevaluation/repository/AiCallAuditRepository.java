package interview.guide.modules.agentevaluation.repository;

import interview.guide.modules.agentevaluation.model.AiCallAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiCallAuditRepository extends JpaRepository<AiCallAuditEntity, String> {

  List<AiCallAuditEntity> findAllByStageIdOrderByAttemptAsc(String stageId);
}
