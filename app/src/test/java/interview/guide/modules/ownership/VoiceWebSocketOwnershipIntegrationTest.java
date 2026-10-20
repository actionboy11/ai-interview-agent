package interview.guide.modules.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.auth.security.CurrentUser;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.service.AuthUserQueryService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewHandshakeInterceptor;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.handler.VoiceInterviewWebSocketHandler;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import interview.guide.modules.voiceinterview.service.DashscopeLlmService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import java.util.HashMap;
import java.util.Optional;
import static org.mockito.Mockito.mock;
import java.net.URI;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoiceWebSocketOwnershipIntegrationTest {
  @Mock CurrentUserProvider currentUser;
  @Mock VoiceInterviewSessionRepository sessions;
  @Mock VoiceInterviewMessageRepository messages;
  @Mock ResumeRepository resumes;
  @Mock ObjectMapper mapper;
  @Mock QwenAsrService asr;
  @Mock QwenTtsService tts;
  @Mock DashscopeLlmService llm;
  @Mock VoiceInterviewProperties properties;
  @Mock ObjectProvider<MeterRegistry> metrics;
  @Mock WebSocketSession socket;
  @Mock AuthUserQueryService users;
  @Mock VoiceInterviewService handshakeInterviews;
  @Mock ServerHttpResponse handshakeResponse;
  @InjectMocks VoiceInterviewService service;
  @InjectMocks VoiceInterviewWebSocketHandler handler;

  @Test
  @DisplayName("语音会话列表不能由公开 userId 参数选择其他用户")
  void publicUserSelectorCannotReadOtherSessions() {
    UUID ownerId = UUID.randomUUID();
    when(currentUser.requireUserId()).thenReturn(ownerId);
    when(sessions.findByOwnerIdOrderByUpdatedAtDesc(ownerId))
        .thenReturn(List.of());
    assertThat(service.getAllSessions(null)).isEmpty();
    verify(sessions).findByOwnerIdOrderByUpdatedAtDesc(ownerId);
  }

  @Test
  @DisplayName("未通过握手归属校验的 WebSocket 不启动语音会话")
  void websocketWithoutVerifiedOwnerIsClosed() throws Exception {
    when(socket.getUri()).thenReturn(URI.create("ws://localhost/ws/voice-interview/81"));
    when(socket.getAttributes()).thenReturn(Map.of());
    handler.afterConnectionEstablished(socket);
    verify(socket).close(CloseStatus.POLICY_VIOLATION);
    handler.destroy();
  }

  @Test
  @DisplayName("其他用户的语音会话 WebSocket 握手按不存在拒绝")
  void foreignVoiceSessionHandshakeIsNotFound() {
    UUID ownerId = UUID.randomUUID();
    var principal = new CurrentUser(ownerId, "first", "First", UserRole.USER, false);
    var context = new SecurityContextImpl();
    context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "", List.of(
        new SimpleGrantedAuthority("ROLE_USER"))));
    var httpSession = new MockHttpSession();
    httpSession.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        context);
    var request = new MockHttpServletRequest("GET", "/ws/voice-interview/81");
    request.setSession(httpSession);
    when(users.requireActive(ownerId)).thenReturn(principal);
    when(handshakeInterviews.requireSessionOwner(81L, ownerId))
        .thenThrow(new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在"));

    var interceptor = new VoiceInterviewHandshakeInterceptor(users, handshakeInterviews);
    var attributes = new HashMap<String, Object>();
    boolean accepted = interceptor.beforeHandshake(new ServletServerHttpRequest(request),
        handshakeResponse, handler, attributes);

    assertThat(accepted).isFalse();
    assertThat(attributes).isEmpty();
    verify(handshakeResponse).setStatusCode(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("语音会话不能关联其他用户的简历")
  void voiceSessionCannotAttachForeignResume() {
    UUID ownerId = UUID.randomUUID();
    when(currentUser.requireUserId()).thenReturn(ownerId);
    var request = CreateSessionRequest.builder().resumeId(91L).build();
    assertThatThrownBy(() -> service.createSession(request))
        .isInstanceOf(BusinessException.class);
    verify(resumes).findByOwnerIdAndId(ownerId, 91L);
  }

  @Test
  @DisplayName("禁用账号的语音评估任务不继续处理")
  void disabledOwnerVoiceEvaluationIsRejected() {
    var owner = new UserEntity();
    owner.setId(UUID.randomUUID());
    owner.setStatus(UserStatus.DISABLED);
    var interview = VoiceInterviewSessionEntity.builder().id(84L).owner(owner).build();
    when(sessions.findById(84L)).thenReturn(Optional.of(interview));
    org.mockito.Mockito.lenient().when(messages.findBySessionIdOrderBySequenceNumAsc(84L))
        .thenReturn(List.of());
    var evaluation = new VoiceInterviewEvaluationService(
        mock(UnifiedEvaluationService.class), mock(LlmProviderRegistry.class),
        mock(VoiceInterviewEvaluationRepository.class), messages, sessions,
        mock(tools.jackson.databind.ObjectMapper.class), mock(InterviewSkillService.class),
        mock(TransactionalExecutor.class));
    assertThatThrownBy(() -> evaluation.generateEvaluation(84L, null))
        .isInstanceOf(BusinessException.class);
  }
}
