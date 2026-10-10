package interview.guide.modules.agentevaluation.service;

public record StageContext(
    String runId,
    String caseId,
    String stageId
) {

  public StageContext {
    if (isBlank(runId) || isBlank(caseId) || isBlank(stageId)) {
      throw new IllegalArgumentException("评测阶段上下文标识不能为空");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
