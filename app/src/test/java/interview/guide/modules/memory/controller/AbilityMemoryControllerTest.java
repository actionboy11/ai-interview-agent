package interview.guide.modules.memory.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.memory.model.AbilityLevel;
import interview.guide.modules.memory.model.AbilityProfileStatus;
import interview.guide.modules.memory.service.AbilityProfileCommandService;
import interview.guide.modules.memory.service.AbilityProfileQueryService;
import interview.guide.modules.memory.service.AbilityProfileSnapshot;
import interview.guide.modules.memory.mapper.AbilityMemoryMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
}
