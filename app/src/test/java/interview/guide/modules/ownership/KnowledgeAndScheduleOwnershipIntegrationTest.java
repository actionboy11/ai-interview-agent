package interview.guide.modules.ownership;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.interviewschedule.service.InterviewScheduleService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseUploadService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseParseService;
import interview.guide.modules.knowledgebase.service.KnowledgeBasePersistenceService;
import interview.guide.modules.knowledgebase.messaging.KnowledgeVectorizationTaskPublisher;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.file.FileStorageService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeAndScheduleOwnershipIntegrationTest {
  private final UUID firstId = UUID.randomUUID();

  @Mock CurrentUserProvider currentUser;
  @Mock InterviewScheduleRepository schedules;
  @Mock KnowledgeBaseRepository knowledgeBases;
  @Mock RagChatMessageRepository messages;
  @Mock KnowledgeBaseMapper mapper;
  @Mock RagChatSessionRepository ragSessions;
  @Mock RagChatMapper ragMapper;
  @Mock KnowledgeBaseQueryService query;
  @Mock KnowledgeBaseQueryProperties queryProperties;
  @Mock FileStorageService storage;
  @Mock FileHashService hashes;
  @Mock FileValidationService validation;
  @Mock KnowledgeBaseParseService parser;
  @Mock KnowledgeBasePersistenceService persistence;
  @Mock KnowledgeVectorizationTaskPublisher publisher;
  @InjectMocks InterviewScheduleService scheduleService;
  @InjectMocks KnowledgeBaseListService knowledgeBaseListService;
  @InjectMocks RagChatSessionService ragService;
  @InjectMocks KnowledgeBaseUploadService uploadService;

  @BeforeEach
  void authenticatedOwner() {
    lenient().when(currentUser.requireUserId()).thenReturn(firstId);
  }

  @Test
  @DisplayName("其他用户的日程 ID 表现为不存在")
  void foreignScheduleIsNotFound() {
    lenient().when(schedules.findById(71L))
        .thenReturn(Optional.of(new InterviewScheduleEntity()));
    assertThatThrownBy(() -> scheduleService.getById(71L))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("其他用户的知识库文件不能下载")
  void foreignKnowledgeBaseCannotBeDownloaded() {
    var foreign = new KnowledgeBaseEntity();
    foreign.setStorageKey("private/file");
    lenient().when(knowledgeBases.findById(72L)).thenReturn(Optional.of(foreign));
    assertThatThrownBy(() -> knowledgeBaseListService.downloadFile(72L))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("RAG 会话不能关联其他用户的知识库")
  void ragSessionCannotAttachForeignKnowledgeBase() {
    assertThatThrownBy(() -> ragService.createSession(new CreateSessionRequest(
        java.util.List.of(73L), "会话")))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("其他用户的 RAG SSE 会话不能新增流式消息")
  void foreignRagStreamCannotPrepareMessage() {
    assertThatThrownBy(() -> ragService.prepareStreamMessage(74L, "问题"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("SSE 回调不能更新其他用户会话的消息")
  void ragCallbackRejectsForeignOwner() {
    var owner = new interview.guide.modules.auth.model.UserEntity();
    owner.setId(UUID.randomUUID());
    var session = new RagChatSessionEntity();
    session.setId(75L);
    session.setOwner(owner);
    var message = new RagChatMessageEntity();
    message.setSession(session);
    when(messages.findById(76L)).thenReturn(Optional.of(message));
    assertThatThrownBy(() -> ragService.completeStreamMessage(76L, 75L, firstId, "内容"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("其他用户不能借重新向量化路径访问知识库")
  void foreignKnowledgeBaseCannotBeRevectorized() {
    var foreign = new KnowledgeBaseEntity();
    foreign.setStorageKey("private/file");
    lenient().when(knowledgeBases.findById(77L)).thenReturn(Optional.of(foreign));
    assertThatThrownBy(() -> uploadService.revectorize(77L))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("所有者可提交旧知识库的异步重新向量化任务")
  void ownerCanRevectorizeLegacyKnowledgeBase() {
    var owned = new KnowledgeBaseEntity();
    owned.setId(78L);
    owned.setName("旧知识库");
    owned.setStorageKey("owned/file");
    when(knowledgeBases.findByOwnerIdAndId(firstId, 78L)).thenReturn(Optional.of(owned));
    uploadService.revectorize(78L);
    verify(persistence).updateVectorStatusToPending(78L);
    verify(publisher).publish(78L);
  }
}
