package interview.guide.modules.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.mapper.AuthResponseMapper;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.CurrentUser;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.auth.service.AuthUserQueryService;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.interviewschedule.service.InterviewScheduleService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.RagChatDTO;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseDeleteService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBasePersistenceService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.config.VoiceInterviewHandshakeInterceptor;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.handler.VoiceInterviewWebSocketHandler;
import interview.guide.modules.voiceinterview.messaging.VoiceEvaluationTaskPublisher;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import interview.guide.modules.voiceinterview.service.DashscopeLlmService;
import interview.guide.modules.resume.repository.ResumeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mapstruct.factory.Mappers;
import org.redisson.api.RedissonClient;
import org.redisson.api.RBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@SpringBootTest(classes = AsyncOwnerPersistenceTest.JpaApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:async-owner;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false"
})
@DisplayName("异步任务在关闭 OSIV 后读取持久化 owner 状态")
class AsyncOwnerPersistenceTest {
  @Configuration(proxyBeanMethods = false)
  @EntityScan(basePackageClasses = {UserEntity.class, KnowledgeBaseEntity.class,
      VoiceInterviewSessionEntity.class, InterviewScheduleEntity.class})
  @EnableJpaRepositories(basePackageClasses = {UserRepository.class,
      KnowledgeBaseRepository.class, VoiceInterviewSessionRepository.class,
      InterviewScheduleRepository.class})
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      TransactionAutoConfiguration.class})
  static class JpaApplication {
  }

  @Autowired UserRepository users;
  @Autowired KnowledgeBaseRepository knowledgeBases;
  @Autowired VoiceInterviewSessionRepository voiceSessions;
  @Autowired VoiceInterviewMessageRepository voiceMessages;
  @Autowired InterviewScheduleRepository schedules;
  @Autowired RagChatSessionRepository ragSessions;
  @Autowired RagChatMessageRepository ragMessages;
  @Autowired TransactionTemplate transactions;

  @AfterEach
  void clearPrincipal() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("WebSocket 握手以持久化临时改密标记拒绝连接，改密后旧会话可重新握手")
  void temporaryPasswordBlocksVoiceHandshake() {
    var owner = user(UserStatus.ACTIVE);
    owner.setMustChangePassword(true);
    users.saveAndFlush(owner);
    var interviews = mock(VoiceInterviewService.class);
    var handshake = new VoiceInterviewHandshakeInterceptor(new AuthUserQueryService(users,
        Mappers.getMapper(AuthResponseMapper.class)), interviews);
    var request = new MockHttpServletRequest("GET", "/ws/voice-interview/42");
    var session = new MockHttpSession();
    var context = new SecurityContextImpl();
    context.setAuthentication(new UsernamePasswordAuthenticationToken(
        new CurrentUser(owner.getId(), owner.getUsername(), owner.getDisplayName(),
            UserRole.USER, false), "", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        context);
    request.setSession(session);
    var response = mock(ServerHttpResponse.class);
    assertThat(handshake.beforeHandshake(new ServletServerHttpRequest(request), response,
        mock(org.springframework.web.socket.WebSocketHandler.class), new HashMap<>())).isFalse();
    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    verify(interviews, never()).requireSessionOwner(org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.any());
    owner = users.findById(owner.getId()).orElseThrow();
    owner.setMustChangePassword(false);
    users.saveAndFlush(owner);
    assertThat(handshake.beforeHandshake(new ServletServerHttpRequest(request),
        mock(ServerHttpResponse.class), mock(org.springframework.web.socket.WebSocketHandler.class),
        new HashMap<>())).isTrue();
  }

  @Test
  @DisplayName("真实身份和仓储限定知识库与日程 CRUD，ADMIN 也不能读改他人内容")
  void tenantKnowledgeAndScheduleCrud() {
    UserEntity owner = user(UserStatus.ACTIVE);
    UserEntity other = user(UserStatus.ACTIVE);
    UserEntity admin = user(UserStatus.ACTIVE, UserRole.ADMIN);
    CurrentUserProvider current = currentUserProvider();
    KnowledgeBasePersistenceService persistence =
        new KnowledgeBasePersistenceService(knowledgeBases, current, users);
    KnowledgeBaseListService listing = new KnowledgeBaseListService(knowledgeBases,
        ragMessages, Mappers.getMapper(KnowledgeBaseMapper.class),
        mock(FileStorageService.class), current);
    KnowledgeBaseDeleteService deletion = new KnowledgeBaseDeleteService(knowledgeBases,
        ragSessions, mock(KnowledgeBaseVectorService.class), mock(FileStorageService.class),
        new TransactionalExecutor(), current);
    InterviewScheduleService scheduleService = new InterviewScheduleService(schedules, current, users);
    CreateInterviewRequest request = new CreateInterviewRequest();
    request.setCompanyName("Acme");
    request.setPosition("Backend");
    request.setInterviewTime(LocalDateTime.now().plusDays(1));

    authenticate(owner);
    Long kbId = persistence.saveKnowledgeBase(new MockMultipartFile("file", "a.txt",
        "text/plain", "content".getBytes()), "owner kb", "java", "owner-key", "url",
        UUID.randomUUID().toString()).getId();
    Long scheduleId = scheduleService.create(request).getId();
    assertThat(listing.getKnowledgeBase(kbId)).isPresent();
    assertThat(scheduleService.getById(scheduleId).getId()).isEqualTo(scheduleId);

    for (UserEntity intruder : List.of(other, admin)) {
      authenticate(intruder);
      assertThat(listing.listKnowledgeBases()).isEmpty();
      assertThat(listing.getKnowledgeBase(kbId)).isEmpty();
      assertThatThrownBy(() -> listing.updateCategory(kbId, "stolen"))
          .isInstanceOfSatisfying(BusinessException.class,
              error -> assertThat(error.getCode()).isEqualTo(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode()));
      assertThatThrownBy(() -> deletion.deleteKnowledgeBase(kbId))
          .isInstanceOfSatisfying(BusinessException.class,
              error -> assertThat(error.getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode()));
      assertThat(scheduleService.getAll(null, null, null)).isEmpty();
      assertThatThrownBy(() -> scheduleService.getById(scheduleId))
          .isInstanceOfSatisfying(BusinessException.class,
              error -> assertThat(error.getCode()).isEqualTo(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND.getCode()));
      assertThatThrownBy(() -> scheduleService.update(scheduleId, request))
          .isInstanceOf(BusinessException.class);
      assertThatThrownBy(() -> scheduleService.delete(scheduleId))
          .isInstanceOf(BusinessException.class);
    }

    authenticate(owner);
    listing.updateCategory(kbId, "updated");
    assertThat(listing.getKnowledgeBase(kbId).orElseThrow().category()).isEqualTo("updated");
    request.setCompanyName("Acme 2");
    assertThat(scheduleService.update(scheduleId, request).getCompanyName()).isEqualTo("Acme 2");
    scheduleService.delete(scheduleId);
    deletion.deleteKnowledgeBase(kbId);
    assertThat(knowledgeBases.findById(kbId)).isEmpty();
    assertThat(schedules.findById(scheduleId)).isEmpty();
  }

  @Test
  @DisplayName("RAG 关联与 SSE 回写只接受持久化 owner 的知识库、会话和消息")
  void tenantRagAttachmentAndSseCallback() {
    UserEntity owner = user(UserStatus.ACTIVE);
    UserEntity other = user(UserStatus.ACTIVE);
    KnowledgeBaseEntity ownKb = knowledgeBase(owner);
    KnowledgeBaseEntity foreignKb = knowledgeBase(other);
    CurrentUserProvider current = currentUserProvider();
    RagChatSessionService rag = new RagChatSessionService(ragSessions, ragMessages,
        knowledgeBases, mock(KnowledgeBaseQueryService.class),
        Mappers.getMapper(RagChatMapper.class), Mappers.getMapper(KnowledgeBaseMapper.class),
        new KnowledgeBaseQueryProperties(), current, users);

    authenticate(owner);
    assertThatThrownBy(() -> transactions.execute(status -> rag.createSession(
        new RagChatDTO.CreateSessionRequest(List.of(foreignKb.getId()), "cross"))))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode()));
    Long ragId = transactions.execute(status -> rag.createSession(
        new RagChatDTO.CreateSessionRequest(List.of(ownKb.getId()), "owner chat"))).id();
    Long assistantId = transactions.execute(status -> rag.prepareStreamMessage(ragId, "question"));

    authenticate(other);
    assertThatThrownBy(() -> transactions.execute(status -> rag.prepareStreamMessage(ragId, "steal")))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode()));
    assertThatThrownBy(() -> transactions.execute(status -> {
      rag.completeStreamMessage(assistantId, ragId, other.getId(), "foreign answer");
      return null;
    })).isInstanceOfSatisfying(BusinessException.class,
        error -> assertThat(error.getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode()));
    assertThat(ragMessages.findById(assistantId).orElseThrow().getCompleted()).isFalse();

    authenticate(owner);
    transactions.execute(status -> {
      rag.completeStreamMessage(assistantId, ragId, owner.getId(), "owner answer");
      return null;
    });
    assertThat(ragMessages.findById(assistantId).orElseThrow().getContent())
        .isEqualTo("owner answer");
  }

  @Test
  @DisplayName("真实持久化允许认证用户创建日程和语音会话")
  void authenticatedOwnerCanCreateScheduleAndVoiceSession() {
    UserEntity owner = user(UserStatus.ACTIVE);
    authenticate(owner);
    CurrentUserProvider current = currentUserProvider();
    CreateInterviewRequest scheduleRequest = new CreateInterviewRequest();
    scheduleRequest.setCompanyName("Company");
    scheduleRequest.setPosition("Developer");
    scheduleRequest.setInterviewTime(LocalDateTime.now().plusDays(2));
    InterviewScheduleService scheduleService = new InterviewScheduleService(schedules, current, users);
    assertThat(scheduleService.create(scheduleRequest).getId()).isNotNull();

    RedissonClient redis = mock(RedissonClient.class);
    @SuppressWarnings("unchecked") RBucket<VoiceInterviewSessionEntity> bucket = mock(RBucket.class);
    when(redis.<VoiceInterviewSessionEntity>getBucket(
        org.mockito.ArgumentMatchers.anyString())).thenReturn(bucket);
    VoiceInterviewService voiceService = new VoiceInterviewService(voiceSessions, voiceMessages,
        mock(VoiceInterviewEvaluationRepository.class), redis, new VoiceInterviewProperties(),
        mock(VoiceEvaluationTaskPublisher.class), mock(LlmProviderRegistry.class), current,
        mock(ResumeRepository.class), users);
    assertThat(voiceService.createSession(CreateSessionRequest.builder().build()).getSessionId())
        .isNotNull();
  }

  @Test
  @DisplayName("活跃 owner 的知识库可在无事务线程完成向量化，禁用 owner 不调用向量存储")
  void vectorJobReadsDetachedOwnerState() {
    UserEntity active = user(UserStatus.ACTIVE);
    UserEntity disabled = user(UserStatus.DISABLED);
    KnowledgeBaseEntity allowed = knowledgeBase(active);
    KnowledgeBaseEntity denied = knowledgeBase(disabled);
    VectorStore store = mock(VectorStore.class);
    KnowledgeBaseVectorService service = new KnowledgeBaseVectorService(store,
        mock(VectorRepository.class), null, knowledgeBases, mock(CurrentUserProvider.class));
    doAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return null;
    }).when(store).add(anyList());

    assertThat(service.vectorizeAndStore(allowed.getId(), "Java interview content for indexing"))
        .isPositive();
    verify(store).add(anyList());
    assertThatThrownBy(() -> service.vectorizeAndStore(denied.getId(), "secret"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("活跃 owner 的语音评估可在无事务线程完成，禁用 owner 在评估前跳过")
  void voiceEvaluationReadsDetachedOwnerState() {
    VoiceInterviewSessionEntity allowed = voiceSession(user(UserStatus.ACTIVE));
    VoiceInterviewSessionEntity denied = voiceSession(user(UserStatus.DISABLED));
    VoiceInterviewMessageRepository messages = mock(VoiceInterviewMessageRepository.class);
    VoiceInterviewEvaluationRepository evaluations = mock(VoiceInterviewEvaluationRepository.class);
    TransactionalExecutor transactions = mock(TransactionalExecutor.class);
    doAnswer(invocation -> {
      invocation.getArgument(0, Runnable.class).run();
      return null;
    }).when(transactions).run(org.mockito.ArgumentMatchers.any(Runnable.class));
    UnifiedEvaluationService externalEvaluator = mock(UnifiedEvaluationService.class);
    VoiceInterviewEvaluationService service = new VoiceInterviewEvaluationService(
        externalEvaluator, mock(LlmProviderRegistry.class), evaluations, messages,
        voiceSessions, mock(tools.jackson.databind.ObjectMapper.class),
        mock(InterviewSkillService.class), transactions);

    assertThat(service.generateEvaluation(allowed.getId(), null))
        .isEqualTo(VoiceInterviewEvaluationService.CompletionResult.CREATED);
    assertThatThrownBy(() -> service.generateEvaluation(denied.getId(), null))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(externalEvaluator);
  }

  @Test
  @DisplayName("已接通语音 socket 的 owner 被禁用后下一帧被拒绝且音频不上送")
  void disabledOwnerCannotContinueEstablishedSocket() throws Exception {
    UserEntity owner = user(UserStatus.ACTIVE);
    UserEntity foreign = user(UserStatus.ACTIVE);
    VoiceInterviewSessionEntity interview = voiceSession(owner);
    voiceMessages.saveAndFlush(VoiceInterviewMessageEntity.builder()
        .sessionId(interview.getId()).messageType("AI_SPEECH")
        .aiGeneratedText("existing question").sequenceNum(0).build());
    AuthUserQueryService userQueries = new AuthUserQueryService(users,
        Mappers.getMapper(AuthResponseMapper.class));
    VoiceInterviewService service = new VoiceInterviewService(voiceSessions, voiceMessages,
        mock(VoiceInterviewEvaluationRepository.class), mock(RedissonClient.class),
        new VoiceInterviewProperties(), mock(VoiceEvaluationTaskPublisher.class),
        mock(LlmProviderRegistry.class), new CurrentUserProvider(userQueries),
        mock(ResumeRepository.class), users);
    VoiceInterviewHandshakeInterceptor handshake =
        new VoiceInterviewHandshakeInterceptor(userQueries, service);
    QwenAsrService audio = mock(QwenAsrService.class);
    VoiceInterviewWebSocketHandler handler = new VoiceInterviewWebSocketHandler(
        new ObjectMapper(), audio, mock(QwenTtsService.class), mock(DashscopeLlmService.class),
        service, new VoiceInterviewProperties(), mock(ObjectProvider.class));
    WebSocketSession socket = mock(WebSocketSession.class);
    String path = "/ws/voice-interview/" + interview.getId();
    Map<String, Object> attributes = new HashMap<>();
    when(socket.getUri()).thenReturn(URI.create("ws://localhost" + path));
    when(socket.getAttributes()).thenReturn(attributes);
    when(socket.isOpen()).thenReturn(true);
    when(socket.getId()).thenReturn("socket-1");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    MockHttpSession httpSession = new MockHttpSession();
    SecurityContextImpl security = new SecurityContextImpl();
    CurrentUser principal = new CurrentUser(owner.getId(), owner.getUsername(),
        owner.getDisplayName(), UserRole.USER, false);
    security.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "",
        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    httpSession.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        security);
    request.setSession(httpSession);
    try {
      ServerHttpResponse anonymousResponse = mock(ServerHttpResponse.class);
      assertThat(handshake.beforeHandshake(new ServletServerHttpRequest(
          new MockHttpServletRequest("GET", path)), anonymousResponse, handler,
          new HashMap<>())).isFalse();
      verify(anonymousResponse).setStatusCode(HttpStatus.UNAUTHORIZED);

      MockHttpServletRequest foreignRequest = new MockHttpServletRequest("GET", path);
      MockHttpSession foreignSession = new MockHttpSession();
      SecurityContextImpl foreignSecurity = new SecurityContextImpl();
      foreignSecurity.setAuthentication(new UsernamePasswordAuthenticationToken(
          new CurrentUser(foreign.getId(), foreign.getUsername(), foreign.getDisplayName(),
              UserRole.USER, false), "", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
      foreignSession.setAttribute(
          HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, foreignSecurity);
      foreignRequest.setSession(foreignSession);
      ServerHttpResponse foreignResponse = mock(ServerHttpResponse.class);
      assertThat(handshake.beforeHandshake(new ServletServerHttpRequest(foreignRequest),
          foreignResponse, handler, new HashMap<>())).isFalse();
      verify(foreignResponse).setStatusCode(HttpStatus.NOT_FOUND);

      assertThat(handshake.beforeHandshake(new ServletServerHttpRequest(request),
          mock(ServerHttpResponse.class), handler, attributes)).isTrue();
      handler.afterConnectionEstablished(socket);
      WebSocketSession reconnect = mock(WebSocketSession.class);
      Map<String, Object> reconnectAttributes = new HashMap<>();
      assertThat(handshake.beforeHandshake(new ServletServerHttpRequest(request),
          mock(ServerHttpResponse.class), handler, reconnectAttributes)).isTrue();
      when(reconnect.getUri()).thenReturn(URI.create("ws://localhost" + path));
      when(reconnect.getAttributes()).thenReturn(reconnectAttributes);
      when(reconnect.getId()).thenReturn("socket-2");
      when(reconnect.isOpen()).thenReturn(true);
      handler.afterConnectionEstablished(reconnect);
      verify(socket).close(CloseStatus.NORMAL);
      owner.setStatus(UserStatus.DISABLED);
      users.saveAndFlush(owner);

      handler.handleMessage(reconnect, new TextMessage("{\"type\":\"audio\",\"data\":\"AQID\"}"));

      verify(reconnect).close(CloseStatus.POLICY_VIOLATION);
      verify(audio, never()).sendAudio(org.mockito.ArgumentMatchers.anyString(),
          org.mockito.ArgumentMatchers.any(byte[].class));
    } finally {
      handler.destroy();
    }
  }

  private UserEntity user(UserStatus status) {
    return user(status, UserRole.USER);
  }

  private UserEntity user(UserStatus status, UserRole role) {
    String name = "owner-" + UUID.randomUUID();
    return users.saveAndFlush(UserEntity.builder().username(name).normalizedUsername(name)
        .passwordHash("hash").displayName(name).role(role).status(status)
        .createdAt(Instant.now()).updatedAt(Instant.now()).build());
  }

  private CurrentUserProvider currentUserProvider() {
    return new CurrentUserProvider(new AuthUserQueryService(users,
        Mappers.getMapper(AuthResponseMapper.class)));
  }

  private void authenticate(UserEntity user) {
    CurrentUser principal = new CurrentUser(user.getId(), user.getUsername(),
        user.getDisplayName(), user.getRole(), false);
    SecurityContextImpl context = new SecurityContextImpl();
    context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "",
        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
    SecurityContextHolder.setContext(context);
  }

  private KnowledgeBaseEntity knowledgeBase(UserEntity owner) {
    KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
    entity.setOwner(owner);
    entity.setName("knowledge");
    entity.setOriginalFilename("knowledge.txt");
    entity.setFileHash(UUID.randomUUID().toString());
    return knowledgeBases.saveAndFlush(entity);
  }

  private VoiceInterviewSessionEntity voiceSession(UserEntity owner) {
    return voiceSessions.saveAndFlush(VoiceInterviewSessionEntity.builder()
        .owner(owner).roleType("java-backend").build());
  }
}
