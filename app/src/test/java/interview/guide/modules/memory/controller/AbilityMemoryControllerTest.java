package interview.guide.modules.memory.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.memory.model.AbilityLevel;
import interview.guide.modules.memory.model.AbilityProfileStatus;
import interview.guide.modules.memory.service.AbilityProfileCommandService;
import interview.guide.modules.memory.service.AbilityProfileQueryService;
import interview.guide.modules.memory.service.AbilityProfileSnapshot;
import interview.guide.modules.memory.mapper.AbilityMemoryMapper;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("能力记忆 HTTP 契约")
class AbilityMemoryControllerTest {
  @Test
  @DisplayName("确认接口只使用登录用户身份，返回统一画像响应")
  void acceptsForCurrentUser() throws Exception {
    UUID owner = UUID.randomUUID();
    var current = mock(CurrentUserProvider.class);
    var commands = mock(AbilityProfileCommandService.class);
    var queries = mock(AbilityProfileQueryService.class);
    var mapper = Mappers.getMapper(AbilityMemoryMapper.class);
    when(current.requireUserId()).thenReturn(owner);
    var snapshot = new AbilityProfileSnapshot(5L, "java", "JAVA", AbilityLevel.COMPETENT,
        68, 0.75, "已确认", 2, AbilityProfileStatus.ACTIVE, false, null, null, 1);
    when(commands.accept(eq(owner), eq(9L), eq(0L))).thenReturn(snapshot);
    var mvc = MockMvcBuilders.standaloneSetup(
        new AbilityMemoryController(current, commands, queries, mapper))
        .setControllerAdvice(new GlobalExceptionHandler()).build();

    mvc.perform(post("/api/ability-memory/proposals/9/accept")
        .contentType("application/json")
        .content("{\"version\":0,\"userId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.score").value(68));
  }

  @Test
  @DisplayName("编辑确认、拒绝、禁用和启用使用当前用户与请求版本")
  void routesAllDecisionCommands() throws Exception {
    UUID owner = UUID.randomUUID();
    var current = mock(CurrentUserProvider.class);
    var commands = mock(AbilityProfileCommandService.class);
    var mapper = Mappers.getMapper(AbilityMemoryMapper.class);
    when(current.requireUserId()).thenReturn(owner);
    var edited = new AbilityProfileSnapshot(5L, "java", "JAVA", AbilityLevel.WEAK,
        68, 0.75, "人工摘要", 2, AbilityProfileStatus.ACTIVE, true, null, null, 3);
    var disabled = new AbilityProfileSnapshot(5L, "java", "JAVA", AbilityLevel.WEAK,
        68, 0.75, "人工摘要", 2, AbilityProfileStatus.DISABLED, true, null, null, 4);
    when(commands.acceptWithEdit(owner, 9L, 2L, AbilityLevel.WEAK, "人工摘要"))
        .thenReturn(edited);
    when(commands.disable(owner, 5L, 3L)).thenReturn(disabled);
    when(commands.enable(owner, 5L, 4L)).thenReturn(edited);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new AbilityMemoryController(current,
        commands, mock(AbilityProfileQueryService.class), mapper))
        .setControllerAdvice(new GlobalExceptionHandler()).build();

    mvc.perform(post("/api/ability-memory/proposals/9/accept-with-edit")
        .contentType("application/json")
        .content("{\"version\":2,\"level\":\"WEAK\",\"summary\":\"人工摘要\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.level").value("WEAK"));
    mvc.perform(post("/api/ability-memory/proposals/9/reject")
        .contentType("application/json").content("{\"version\":2}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    mvc.perform(post("/api/ability-memory/profiles/5/disable")
        .contentType("application/json").content("{\"version\":3}"))
        .andExpect(jsonPath("$.data.status").value("DISABLED"));
    mvc.perform(post("/api/ability-memory/profiles/5/enable")
        .contentType("application/json").content("{\"version\":4}"))
        .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    verify(commands).acceptWithEdit(owner, 9L, 2L, AbilityLevel.WEAK, "人工摘要");
    verify(commands).reject(owner, 9L, 2L);
    verify(commands).disable(owner, 5L, 3L);
    verify(commands).enable(owner, 5L, 4L);
  }

  @Test
  @DisplayName("读取仅以当前用户查询，陈旧版本与无效请求返回统一业务错误")
  void readAndValidationContract() throws Exception {
    UUID owner = UUID.randomUUID();
    var current = mock(CurrentUserProvider.class);
    var commands = mock(AbilityProfileCommandService.class);
    var queries = mock(AbilityProfileQueryService.class);
    when(current.requireUserId()).thenReturn(owner);
    when(queries.profiles(owner, "java")).thenReturn(List.of());
    when(commands.accept(owner, 9L, 1L))
        .thenThrow(new BusinessException(ErrorCode.ABILITY_DECISION_CONFLICT));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new AbilityMemoryController(current,
        commands, queries, Mappers.getMapper(AbilityMemoryMapper.class)))
        .setControllerAdvice(new GlobalExceptionHandler()).build();

    mvc.perform(get("/api/ability-memory/profiles").param("skillId", "java")
        .param("ownerId", UUID.randomUUID().toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    mvc.perform(post("/api/ability-memory/proposals/9/accept")
        .contentType("application/json").content("{\"version\":1}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(13003));
    mvc.perform(post("/api/ability-memory/proposals/9/accept")
        .contentType("application/json").content("{}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
    verify(queries).profiles(owner, "java");
  }
}
