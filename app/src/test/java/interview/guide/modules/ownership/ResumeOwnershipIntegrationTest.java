package interview.guide.modules.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.rabbitmq.client.Channel;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.resume.deadletter.ResumeAnalysisDeadLetterEntity;
import interview.guide.modules.resume.deadletter.ResumeAnalysisDeadLetterStatus;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.messaging.rabbit.ResumeAnalysisRabbitConsumer;
import interview.guide.modules.resume.messaging.rabbit.ResumeAnalysisRabbitProducer;
import interview.guide.modules.resume.messaging.rabbit.ResumeAnalysisRetryPolicy;
import interview.guide.modules.resume.messaging.rabbit.ResumeAnalysisMessage;
import interview.guide.modules.resume.service.ResumeGradingService;
import interview.guide.modules.resume.messaging.ResumeAnalysisTaskPublisher;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;

@DisplayName("简历工作流双用户隔离")
class ResumeOwnershipIntegrationTest extends WorkflowOwnershipTestSupport {
  @Test
  @DisplayName("上传和重试读取旧键都在事务外，上传按当前用户绑定")
  void uploadAndLegacyReanalysis() {
    appConfig.setAllowedTypes(List.of("text/plain"));
    var file = new MockMultipartFile("file", "resume.txt", "text/plain", "upload test".getBytes(StandardCharsets.UTF_8));
    when(parser.detectContentType(file)).thenReturn("text/plain");
    when(parser.parseResume(file)).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return "Resume";
    });
    var result = resumeUpload.uploadAndAnalyze(file);
    assertThat(result.get("duplicate")).isEqualTo(false);
    assertThatThrownBy(() -> resumeUpload.uploadAndAnalyze(file))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.RESUME_DUPLICATE.getCode()));
    when(currentUser.requireUserId()).thenReturn(second.getId());
    assertThat(resumeUpload.uploadAndAnalyze(file).get("duplicate")).isEqualTo(false);
    when(currentUser.requireUserId()).thenReturn(first.getId());
    var legacy = resume(first, UUID.randomUUID().toString());
    legacy.setResumeText(null);
    resumes.saveAndFlush(legacy);
    when(parser.downloadAndParseContent("resumes/legacy.txt", "resume.txt"))
        .thenAnswer(invocation -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          return "Recovered";
        });
    resumeUpload.reanalyze(legacy.getId());
    assertThat(resumes.findById(legacy.getId()).orElseThrow().getResumeText()).isEqualTo("Recovered");
  }

  @Test
  @DisplayName("并发重复入库由所有者哈希唯一约束映射为简历重复错误")
  void mapsUniqueConstraintToDuplicateError() {
    var file = new MockMultipartFile("file", "resume.txt", "text/plain",
        "race test".getBytes(StandardCharsets.UTF_8));
    resumePersistence.saveResume(file, "Resume", "resumes/first", "url");
    assertThatThrownBy(() -> resumePersistence.saveResume(file, "Resume", "resumes/second", "url"))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.RESUME_DUPLICATE.getCode()));
  }

  @Test
  @DisplayName("其他数据库约束错误不能伪装为简历重复")
  void doesNotMisclassifyOtherConstraintErrors() {
    var file = new MockMultipartFile("file", "resume.txt", "text/plain",
        "missing owner".getBytes(StandardCharsets.UTF_8));
    when(currentUser.requireUserId()).thenReturn(UUID.randomUUID());
    assertThatThrownBy(() -> resumePersistence.saveResume(file, "Resume", "resumes/key", "url"))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.RESUME_UPLOAD_FAILED.getCode()));
  }

  @Test
  @DisplayName("简历异步按持久化简历处理，不依赖用户请求上下文")
  void resumeWorkerDoesNotRequireRequestIdentity() throws Exception {
    var own = resume(first, UUID.randomUUID().toString());
    when(currentUser.requireUserId()).thenThrow(new AssertionError("Worker must not require request identity"));
    var grading = mock(ResumeGradingService.class);
    when(grading.analyzeResume("private resume")).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return new ResumeAnalysisResponse(80, new ResumeAnalysisResponse.ScoreDetail(80, 80, 80, 80, 80),
          "Good", List.of(), List.of(), "private resume");
    });
    var consumer = new ResumeAnalysisRabbitConsumer(grading, resumePersistence,
        mock(ResumeAnalysisRabbitProducer.class), mock(ResumeAnalysisRetryPolicy.class));
    var channel = mock(Channel.class);
    var id = UUID.randomUUID();
    var properties = new MessageProperties();
    properties.setDeliveryTag(1);
    consumer.consume(new ResumeAnalysisMessage(id, own.getId(), 0, OffsetDateTime.now(), id),
        new Message(new byte[0], properties), channel);
    assertThat(resumes.findById(own.getId()).orElseThrow().getAnalyzeStatus())
        .isEqualTo(AsyncTaskStatus.COMPLETED);
    verify(channel).basicAck(1, false);
    doReturn(first.getId()).when(currentUser).requireUserId();
    assertThat(resumePersistence.getLatestAnalysisAsDTO(own.getId())).get()
        .extracting(ResumeAnalysisResponse::overallScore).isEqualTo(80);
    assertThat(resumeHistory.getResumeDetail(own.getId()).analyses()).hasSize(1);
    when(pdf.exportResumeAnalysis(any(), any())).thenReturn(new byte[] {1, 2});
    assertThat(resumeHistory.exportAnalysisPdf(own.getId()).pdfBytes()).containsExactly(1, 2);
  }

  @Test
  @DisplayName("管理员死信列表、详情、重放也不能读取他人或已删除的简历")
  void isolatesDeadLettersBeforeReplayClaim() {
    first.setRole(UserRole.ADMIN);
    users.saveAndFlush(first);
    var own = resume(first, UUID.randomUUID().toString());
    var foreign = resume(second, UUID.randomUUID().toString());
    var ownedDead = deadLetter(own.getId());
    var foreignDead = deadLetter(foreign.getId());
    var orphan = deadLetter(Long.MAX_VALUE);
    assertThat(resumeDeadLetterService.list(null, 0, 100).items())
        .extracting(value -> value.id()).containsExactly(ownedDead.getId());
    assertThat(resumeDeadLetterService.get(ownedDead.getId()).id()).isEqualTo(ownedDead.getId());
    for (var rejected : List.of(foreignDead, orphan)) {
      assertThatThrownBy(() -> resumeDeadLetterService.get(rejected.getId()))
          .isInstanceOf(BusinessException.class);
      assertThatThrownBy(() -> resumeDeadLetterService.replay(rejected.getId()))
          .isInstanceOf(BusinessException.class);
      assertThat(resumeDeadLetters.findById(rejected.getId()).orElseThrow().getStatus())
          .isEqualTo(ResumeAnalysisDeadLetterStatus.PENDING);
    }
    when(resumePublisher.publish(own.getId()))
        .thenReturn(new ResumeAnalysisTaskPublisher.PublishReceipt(UUID.randomUUID()));
    assertThat(resumeDeadLetterService.replay(ownedDead.getId()).status())
        .isEqualTo(ResumeAnalysisDeadLetterStatus.REPLAYED);
  }

  private ResumeAnalysisDeadLetterEntity deadLetter(Long resumeId) {
    var dead = new ResumeAnalysisDeadLetterEntity();
    dead.setResumeId(resumeId);
    dead.setRetryCount(3);
    dead.setOriginalMessageId(UUID.randomUUID().toString());
    dead.setFailedAt(OffsetDateTime.now());
    return resumeDeadLetters.saveAndFlush(dead);
  }

  @ParameterizedTest
  @ValueSource(strings = {"detail", "export", "delete", "reanalyze", "analysis", "analyses"})
  @DisplayName("他人的简历与不存在的简历返回相同错误")
  void rejectsForeignResume(String operation) {
    var foreign = resume(second, UUID.randomUUID().toString());
    assertThat(resumePersistence.findById(foreign.getId())).isEmpty();
    assertThatThrownBy(() -> {
      switch (operation) {
        case "detail" -> resumeHistory.getResumeDetail(foreign.getId());
        case "export" -> resumeHistory.exportAnalysisPdf(foreign.getId());
        case "delete" -> resumeDelete.deleteResume(foreign.getId());
        case "reanalyze" -> resumeUpload.reanalyze(foreign.getId());
        case "analysis" -> resumePersistence.getLatestAnalysis(foreign.getId());
        default -> resumePersistence.findAnalysesByResumeId(foreign.getId());
      }
    }).isInstanceOfSatisfying(BusinessException.class,
        error -> assertThat(error.getCode()).isEqualTo(ErrorCode.RESUME_NOT_FOUND.getCode()));
    assertThat(resumes.findById(foreign.getId())).isPresent();
  }

  @Test
  @DisplayName("列表仅返回自己的简历，重复内容在各用户之间独立")
  void isolatesListDuplicateAndCreate() {
    var file = new MockMultipartFile("file", "resume.txt", "text/plain",
        "same content".getBytes(StandardCharsets.UTF_8));
    var a = resumePersistence.saveResume(file, "text", "key-a", "url-a");
    when(currentUser.requireUserId()).thenReturn(second.getId());
    assertThat(resumePersistence.findExistingResume(file)).isEmpty();
    var b = resumePersistence.saveResume(file, "text", "key-b", "url-b");
    assertThat(a.getOwner().getId()).isEqualTo(first.getId());
    assertThat(b.getOwner().getId()).isEqualTo(second.getId());
    assertThat(resumePersistence.findExistingResume(file)).get()
        .extracting(value -> value.getId()).isEqualTo(b.getId());
    assertThat(resumeHistory.getAllResumes()).extracting(value -> value.id()).containsExactly(b.getId());
  }

  @Test
  @DisplayName("新简历对象键包含用户 ID，旧对象键仍可删除")
  void namespacesStorageAndPreservesLegacyKey() {
    var file = new MockMultipartFile("file", "resume.txt", "text/plain", new byte[] {1});
    assertThat(storage.uploadResume(file)).startsWith("resumes/" + first.getId() + "/");
    var request = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3).putObject(request.capture(), any(RequestBody.class));
    assertThat(request.getValue().key()).contains(first.getId().toString());
    var own = resume(first, UUID.randomUUID().toString());
    when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return DeleteObjectResponse.builder().build();
    });
    resumeDelete.deleteResume(own.getId());
    var deletion = ArgumentCaptor.forClass(DeleteObjectRequest.class);
    verify(s3).deleteObject(deletion.capture());
    assertThat(deletion.getValue().key()).isEqualTo("resumes/legacy.txt");
    assertThat(resumes.findById(own.getId())).isEmpty();
  }
}
