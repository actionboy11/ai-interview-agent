package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.mapper.InterviewMapper;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.repository.DynamicEvaluationOutboxRepository;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.memory.service.AbilityMemoryDeletionService;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.mapstruct.factory.Mappers;
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
    List<String> batchPrompts = new ArrayList<>();
    when(invoker.invoke(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(call -> {
          String context = call.getArgument(6);
          if ("总结评估".equals(context)) {
            return construct("SummaryDTO", "总结", List.of(), List.of());
          }
          batchPrompts.add(call.getArgument(2));
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
    assertThat(report.questionDetails().getFirst().score()).isEqualTo(83);
    assertThat(batchPrompts.getFirst()).contains("questionIndex=0");
    InterviewReportDTO.QuestionEvaluation detail = new InterviewReportDTO.QuestionEvaluation(
        0, "索引是什么？", "MYSQL", "数据库", "B 树", 83, "准确");
    assertThat(detail.categoryKey()).isEqualTo("MYSQL");
  }

  @Test
  @DisplayName("模型返回错误类别键时拒绝整份评估")
  void rejectsChangedModelCategoryKey() throws Exception {
    StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
    when(invoker.invoke(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(call -> "总结评估".equals(call.getArgument(6))
            ? construct("SummaryDTO", "总结", List.of(), List.of())
            : construct("BatchReportDTO", 90, "好", List.of(), List.of(),
                List.of(construct("QuestionEvalDTO", 0, "JAVA", 90, "反馈", "参考", List.of()))));
    UnifiedEvaluationService unified = new UnifiedEvaluationService(
        invoker, new DefaultResourceLoader(), new InterviewEvaluationProperties());

    assertThatThrownBy(() -> unified.evaluate(mock(ChatClient.class), "session",
        List.of(new QaRecord(0, "索引是什么？", "MYSQL", "MySQL", "B 树")), null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("categoryKey");
  }

  @Test
  @DisplayName("结构化结果顺序变化时按原始 questionIndex 还原分数")
  void acceptsReorderedValidResults() throws Exception {
    EvaluationReport report = evaluateResults(List.of(
        new QaRecord(0, "第一题", "MYSQL", "MySQL", "回答一"),
        new QaRecord(1, "第二题", "JAVA", "Java", "回答二")), List.of(
        construct("QuestionEvalDTO", 1, "JAVA", 20, "二", "", List.of()),
        construct("QuestionEvalDTO", 0, "MYSQL", 90, "一", "", List.of())));

    assertThat(report.questionDetails()).extracting(EvaluationReport.QuestionEvaluation::score)
        .containsExactly(90, 20);
  }

  @Test
  @DisplayName("重复题号和缺失题号不会写出伪造零分报告")
  void rejectsDuplicateAndMissingIndices() throws Exception {
    List<QaRecord> questions = List.of(
        new QaRecord(0, "第一题", "MYSQL", "MySQL", "回答一"),
        new QaRecord(1, "第二题", "JAVA", "Java", "回答二"));
    Object first = construct("QuestionEvalDTO", 0, "MYSQL", 90, "一", "", List.of());

    assertThatThrownBy(() -> evaluateResults(questions, List.of(first, first)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> evaluateResults(questions, List.of(first)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> evaluateResults(questions, List.of(first,
        construct("QuestionEvalDTO", 2, "JAVA", 20, "二", "", List.of()))))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("真实零分且题号类别键正确时保留零分评估")
  void keepsGenuineZeroScore() throws Exception {
    EvaluationReport report = evaluateResults(
        List.of(new QaRecord(0, "索引？", "MYSQL", "MySQL", "不知道")),
        List.of(construct("QuestionEvalDTO", 0, "MYSQL", 0, "未作答", "", List.of())));

    assertThat(report.questionDetails().getFirst().score()).isZero();
    assertThat(report.questionDetails().getFirst().feedback()).isEqualTo("未作答");
  }

  private EvaluationReport evaluateResults(List<QaRecord> questions, List<Object> results)
      throws Exception {
    StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
    when(invoker.invoke(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(call -> "总结评估".equals(call.getArgument(6))
            ? construct("SummaryDTO", "总结", List.of(), List.of())
            : construct("BatchReportDTO", 80, "好", List.of(), List.of(), results));
    UnifiedEvaluationService unified = new UnifiedEvaluationService(
        invoker, new DefaultResourceLoader(), new InterviewEvaluationProperties());
    return unified.evaluate(mock(ChatClient.class), "session", questions, null);
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
    session.setQuestionsJson("[]");
    session.setOwner(UserEntity.builder().id(UUID.randomUUID()).build());
    when(sessions.findBySessionIdWithResume("session")).thenReturn(Optional.of(session));
    when(sessions.findBySessionIdForDerivation("session")).thenReturn(Optional.of(session));
    when(answers.findBySession_SessionIdOrderByQuestionIndex("session")).thenReturn(List.of());
    when(skills.getSkill("java-backend")).thenReturn(new InterviewSkillService.SkillDTO(
        "java-backend", "Java", "", List.of(new InterviewSkillService.SkillCategoryDTO(
            "MYSQL", "数据库", "", null, false)), true, null, null, null));
    InterviewPersistenceService persistence = new InterviewPersistenceService(sessions, answers,
        mock(ResumeRepository.class), new ObjectMapper(), mock(CurrentUserProvider.class),
        mock(UserRepository.class),
        mock(AbilityMemoryDeletionService.class),
        mock(DynamicInterviewRuntimeRepository.class),
        mock(DynamicEvaluationOutboxRepository.class));
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
    var historical = Mappers.getMapper(InterviewMapper.class)
        .toAnswerDetailDTO(saved, List.of());
    assertThat(historical.categoryKey()).isEqualTo("MYSQL");
    assertThat(historical.categoryLabel()).isEqualTo("数据库");
    assertThat(historical.category()).isEqualTo("数据库");
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
