package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.infrastructure.mapper.InterviewMapper;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.dynamic.model.DynamicGroupEvaluation;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("动态报告可空分数导出")
class DynamicReportExportTest {
  @Test
  @DisplayName("映射动态未观察分数保持空值而固定模式保留旧默认零分")
  void mappingPreservesModeSpecificNullSemantics() {
    var mapper = Mappers.getMapper(InterviewMapper.class);
    var session = new InterviewSessionEntity();
    session.setExecutionMode(InterviewExecutionMode.DYNAMIC_FOLLOW_UP);
    var answer = new InterviewAnswerEntity();
    answer.setSession(session);
    answer.setQuestionIndex(0);
    assertThat(mapper.toQuestionEvaluation(answer).score()).isNull();
    answer.setScore(0);
    assertThat(mapper.toQuestionEvaluation(answer).score()).isZero();
    answer.setScore(null);
    session.setExecutionMode(InterviewExecutionMode.LEGACY_FIXED);
    assertThat(mapper.toQuestionEvaluation(answer).score()).isZero();
  }
  @Test
  @DisplayName("PDF区分未评估与真实零分且不拆箱空分数")
  void pdfPreservesNullAndZero() throws Exception {
    var session = new InterviewSessionEntity();
    session.setSessionId("dynamic");
    session.setExecutionMode(InterviewExecutionMode.DYNAMIC_FOLLOW_UP);
    session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    session.setTotalQuestions(2);
    var skipped = new InterviewAnswerEntity();
    skipped.setQuestionIndex(0);
    skipped.setQuestion("跳过题目");
    var zero = new InterviewAnswerEntity();
    zero.setQuestionIndex(1);
    zero.setQuestion("真实零分题目");
    zero.setScore(0);
    session.setAnswers(List.of(skipped, zero));
    byte[] bytes = new PdfExportService(JsonMapper.builder().build()).exportInterviewReport(session);
    try (var pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(bytes)))) {
      StringBuilder text = new StringBuilder();
      for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
        text.append(PdfTextExtractor.getTextFromPage(pdf.getPage(page)));
      }
      assertThat(text.toString()).contains("未评估", "0/100").doesNotContain("null/100");
    }
  }

  @Test
  @DisplayName("PDF导出正式组分数证据与有效主问题组数量")
  void pdfExportsFormalGroupEvidence() throws Exception {
    var mapper = JsonMapper.builder().build();
    var report = new InterviewReportDTO("dynamic", 4, 60,
        List.of(new InterviewReportDTO.CategoryScore("JAVA", "Java", 60, 2)), List.of(),
        "总体", List.of(), List.of(), List.of(),
        List.of(new DynamicGroupEvaluation("one", 40, "基础不足", List.of(0, 1, 2), List.of("t1")),
            new DynamicGroupEvaluation("two", 80, "应用正确", List.of(3), List.of("t2"))),
        InterviewExecutionMode.DYNAMIC_FOLLOW_UP, 1, 9L);
    var session = new InterviewSessionEntity();
    session.setSessionId("dynamic");
    session.setExecutionMode(InterviewExecutionMode.DYNAMIC_FOLLOW_UP);
    session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    session.setOverallScore(60);
    session.setReportJson(mapper.writeValueAsString(report));
    byte[] bytes = new PdfExportService(mapper).exportInterviewReport(session);
    try (var pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(bytes)))) {
      var text = new StringBuilder();
      for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
        text.append(PdfTextExtractor.getTextFromPage(pdf.getPage(page)));
      }
      assertThat(text.toString()).contains("主问题组评价", "40/100", "80/100", "有效主问题组: 2",
          "基础不足", "应用正确", "来源题号: 1, 2, 3");
    }
  }
}
