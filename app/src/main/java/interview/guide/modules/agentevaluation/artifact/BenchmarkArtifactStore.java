package interview.guide.modules.agentevaluation.artifact;

public interface BenchmarkArtifactStore {

  String put(
      String runId,
      String caseId,
      String stageId,
      String artifactType,
      byte[] plaintext
  );

  byte[] get(
      String key,
      String runId,
      String caseId,
      String stageId,
      String artifactType
  );
}
