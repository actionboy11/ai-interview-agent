package interview.guide.modules.agentevaluation.service;

import interview.guide.modules.agentevaluation.model.AgentEvaluationStageType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class StageIdempotencyKeyFactory {

  public String create(
      String runId,
      String caseKey,
      AgentEvaluationStageType stageType,
      String inputHash,
      String promptVersion,
      String model
  ) {
    String material = lengthPrefix(runId)
        + lengthPrefix(caseKey)
        + lengthPrefix(stageType.name())
        + lengthPrefix(inputHash)
        + lengthPrefix(promptVersion)
        + lengthPrefix(model);
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(material.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
    }
  }

  private String lengthPrefix(String value) {
    return value.length() + ":" + value;
  }
}
