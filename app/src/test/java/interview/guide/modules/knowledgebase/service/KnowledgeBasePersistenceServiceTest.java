package interview.guide.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KnowledgeBasePersistenceServiceTest {

  private final KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
  private final KnowledgeBasePersistenceService service =
      new KnowledgeBasePersistenceService(repository);

  @Test
  @DisplayName("首次完成向量化时记录消息标识和分块数")
  void shouldCompleteVectorizationOnce() {
    var knowledgeBase = new KnowledgeBaseEntity();
    knowledgeBase.setId(42L);
    when(repository.findById(42L)).thenReturn(Optional.of(knowledgeBase));
    when(repository.existsByVectorizationMessageId("message-1")).thenReturn(false);

    var result = service.completeVectorization(42L, 7, "message-1");

    assertThat(result).isEqualTo(
        KnowledgeBasePersistenceService.CompletionResult.COMPLETED
    );
    assertThat(knowledgeBase.getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
    assertThat(knowledgeBase.getChunkCount()).isEqualTo(7);
    assertThat(knowledgeBase.getVectorizationMessageId()).isEqualTo("message-1");
    verify(repository).save(knowledgeBase);
  }

  @Test
  @DisplayName("已完成的知识库不会被重复完成")
  void shouldIgnoreCompletedKnowledgeBase() {
    var knowledgeBase = new KnowledgeBaseEntity();
    knowledgeBase.setId(42L);
    knowledgeBase.setVectorStatus(VectorStatus.COMPLETED);
    when(repository.findById(42L)).thenReturn(Optional.of(knowledgeBase));

    var result = service.completeVectorization(42L, 9, "message-2");

    assertThat(result).isEqualTo(
        KnowledgeBasePersistenceService.CompletionResult.ALREADY_COMPLETED
    );
    verify(repository, never()).save(knowledgeBase);
  }
}
