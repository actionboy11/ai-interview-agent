package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.InterviewEvaluationProperties;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

class StableCategoryEvaluationTest {

  @Test
  @DisplayName("稳定类别键贯穿题目、通用评估、报告和答案实体")
  void keepsCategoryKeyWhenDisplayLabelChanges() throws Exception {
    StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
    when(invoker.invoke(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(call -> {
          String context = call.getArgument(6);
          if ("总结评估".equals(context)) {
            return construct("SummaryDTO", "总结", List.of(), List.of());
          }
          return construct("BatchReportDTO", 83, "好", List.of(), List.of(),
              List.of(construct("QuestionEvalDTO", 0, "MYSQL", 83, "准确", "参考", List.of())));
        });
    UnifiedEvaluationService unified = new UnifiedEvaluationService(
        invoker, new DefaultResourceLoader(), new InterviewEvaluationProperties());
    InterviewQuestionDTO question = InterviewQuestionDTO.create(0, "索引是什么？", "MYSQL", "数据库").withAnswer("B 树");
    QaRecord qa = new QaRecord(question.questionIndex(), question.question(), question.type(),
        question.category(), question.userAnswer());

    EvaluationReport report = unified.evaluate(mock(ChatClient.class), "session", List.of(qa), null);

    assertThat(report.questionDetails().getFirst().categoryKey()).isEqualTo("MYSQL");
    assertThat(report.questionDetails().getFirst().categoryLabel()).isEqualTo("数据库");
    assertThat(report.categoryScores().getFirst().categoryKey()).isEqualTo("MYSQL");
    assertThat(report.categoryScores().getFirst().categoryLabel()).isEqualTo("数据库");
    InterviewReportDTO.QuestionEvaluation detail = new InterviewReportDTO.QuestionEvaluation(
        0, "索引是什么？", "MYSQL", "数据库", "B 树", 83, "准确");
    assertThat(detail.categoryKey()).isEqualTo("MYSQL");
  }

  @Test
  @DisplayName("模型返回错误类别键时使用会话题目的稳定键和本地标签")
  void rejectsChangedModelCategoryKey() throws Exception {
    StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
    when(invoker.invoke(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(call -> "总结评估".equals(call.getArgument(6))
            ? construct("SummaryDTO", "总结", List.of(), List.of())
            : construct("BatchReportDTO", 90, "好", List.of(), List.of(),
                List.of(construct("QuestionEvalDTO", 0, "JAVA", 90, "反馈", "参考", List.of()))));
    UnifiedEvaluationService unified = new UnifiedEvaluationService(
        invoker, new DefaultResourceLoader(), new InterviewEvaluationProperties());

    EvaluationReport report = unified.evaluate(mock(ChatClient.class), "session",
        List.of(new QaRecord(0, "索引是什么？", "MYSQL", "MySQL", "B 树")), null);

    assertThat(report.questionDetails().getFirst().categoryKey()).isEqualTo("MYSQL");
    assertThat(report.questionDetails().getFirst().categoryLabel()).isEqualTo("MySQL");
    assertThat(report.questionDetails().getFirst().score()).isZero();
  }

  @Test
  @DisplayName("题目类型经适配器和报告写入答案稳定键，展示标签由本地 Skill 修正")
  @SuppressWarnings({"unchecked", "rawtypes"})
  void propagatesKeyThroughAdapterAndPersistence() {
    UnifiedEvaluationService unified = mock(UnifiedEvaluationService.class);
    InterviewSkillService skills = mock(InterviewSkillService.class);
    InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
    InterviewAnswerRepository answers = mock(InterviewAnswerRepository.class);
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("session");
    session.setSkillId("java-backend");
    session.setOwner(UserEntity.builder().id(UUID.randomUUID()).build());
    when(sessions.findBySessionIdWithResume("session")).thenReturn(Optional.of(session));
    when(answers.findBySession_SessionIdOrderByQuestionIndex("session")).thenReturn(List.of());
    when(skills.getSkill("java-backend")).thenReturn(new InterviewSkillService.SkillDTO(
        "java-backend", "Java", "", List.of(new InterviewSkillService.SkillCategoryDTO(
            "MYSQL", "数据库", "", null, false)), true, null, null, null));
    InterviewPersistenceService persistence = new InterviewPersistenceService(sessions, answers,
        mock(ResumeRepository.class), new ObjectMapper(), mock(CurrentUserProvider.class),
        mock(UserRepository.class));
    EvaluationReport commonReport = new EvaluationReport("session", 1, 83,
        List.of(new EvaluationReport.CategoryScore("MYSQL", "数据库", 83, 1)),
        List.of(new EvaluationReport.QuestionEvaluation(0, "索引？", "MYSQL", "数据库",
            "B 树", 83, "准确")), "好", List.of(), List.of(), List.of());
    when(unified.evaluate(any(), any(), any(), nullable(String.class), nullable(String.class)))
        .thenReturn(commonReport);
    AnswerEvaluationService adapter = new AnswerEvaluationService(unified, persistence, skills);

    InterviewReportDTO report = adapter.evaluateInterview(mock(ChatClient.class), "session", null,
        List.of(InterviewQuestionDTO.create(0, "索引？", "MYSQL", "旧标签").withAnswer("B 树")));
    ArgumentCaptor<List> qaCaptor = ArgumentCaptor.forClass(List.class);
    verify(unified).evaluate(any(), any(), qaCaptor.capture(), nullable(String.class),
        nullable(String.class));
    QaRecord qa = (QaRecord) qaCaptor.getValue().getFirst();
    assertThat(qa.categoryKey()).isEqualTo("MYSQL");
    assertThat(qa.categoryLabel()).isEqualTo("数据库");
    assertThat(report.questionDetails().getFirst().categoryKey()).isEqualTo("MYSQL");

    persistence.saveReportInternal("session", report);
    ArgumentCaptor<List> answerCaptor = ArgumentCaptor.forClass(List.class);
    verify(answers).saveAll(answerCaptor.capture());
    InterviewAnswerEntity saved = (InterviewAnswerEntity) answerCaptor.getValue().getFirst();
    assertThat(saved.getCategoryKey()).isEqualTo("MYSQL");
    assertThat(saved.getCategoryLabel()).isEqualTo("数据库");
  }

  @Test
  @DisplayName("旧语音评估 JSON 的 category 仍能读为展示标签")
  void readsLegacyVoiceEvaluationJson() {
    ObjectMapper mapper = new ObjectMapper();
    EvaluationReport.QuestionEvaluation item = mapper.readValue("""
        {"questionIndex":0,"question":"索引？","category":"技术问题",
         "userAnswer":"B 树","score":80,"feedback":"正确"}
        """, EvaluationReport.QuestionEvaluation.class);

    assertThat(item.categoryKey()).isEqualTo("OTHER");
    assertThat(item.categoryLabel()).isEqualTo("技术问题");
    assertThat(mapper.writeValueAsString(item)).contains("\"category\":\"技术问题\"");
    assertThat(new QaRecord(0, "问题", "技术问题", "回答").categoryKey())
        .isEqualTo("TECHNICAL");
    assertThat(new QaRecord(1, "问题", "项目深挖", "回答").categoryKey())
        .isEqualTo("PROJECT");
  }

  private Object construct(String name, Object... args) throws Exception {
    Class<?> type = Class.forName(UnifiedEvaluationService.class.getName() + "$" + name);
    Constructor<?> constructor = type.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    return constructor.newInstance(args);
  }
}
