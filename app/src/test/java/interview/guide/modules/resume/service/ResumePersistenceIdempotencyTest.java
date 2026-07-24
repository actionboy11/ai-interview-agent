package interview.guide.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.mapper.ResumeMapper;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeAnalysisRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ResumePersistenceIdempotencyTest {

    private ResumeRepository resumeRepository;
    private ResumeAnalysisRepository analysisRepository;
    private ResumeMapper resumeMapper;
    private ResumePersistenceService service;
    private ResumeEntity resume;

    @BeforeEach
    void setUp() {
        resumeRepository = mock(ResumeRepository.class);
        analysisRepository = mock(ResumeAnalysisRepository.class);
        resumeMapper = mock(ResumeMapper.class);
        service = new ResumePersistenceService(
            resumeRepository,
            analysisRepository,
            new ObjectMapper(),
            resumeMapper,
            mock(FileHashService.class)
        );
        resume = new ResumeEntity();
        resume.setId(42L);
        when(resumeRepository.findById(42L)).thenReturn(Optional.of(resume));
    }

    @Test
    void shouldCompleteOnceForSameMessageId() {
        ResumeAnalysisEntity entity = new ResumeAnalysisEntity();
        when(resumeMapper.toAnalysisEntity(any())).thenReturn(entity);
        when(analysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.completeAnalysis(42L, "message-1", response());

        assertThat(result).isEqualTo(ResumePersistenceService.CompletionResult.CREATED);
        assertThat(entity.getAnalysisMessageId()).isEqualTo("message-1");
        assertThat(resume.getAnalyzeStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    }

    @Test
    void shouldIgnoreAlreadyCompletedMessage() {
        when(analysisRepository.existsByAnalysisMessageId("message-1")).thenReturn(true);

        var result = service.completeAnalysis(42L, "message-1", response());

        assertThat(result).isEqualTo(ResumePersistenceService.CompletionResult.ALREADY_COMPLETED);
        verify(analysisRepository, never()).save(any());
    }

    @Test
    void shouldTruncateFailureReason() {
        service.markAnalysisFailed(42L, "x".repeat(600));

        assertThat(resume.getAnalyzeError()).hasSize(500);
        assertThat(resume.getAnalyzeStatus()).isEqualTo(AsyncTaskStatus.FAILED);
    }

    private ResumeAnalysisResponse response() {
        return new ResumeAnalysisResponse(
            80,
            new ResumeAnalysisResponse.ScoreDetail(20, 16, 20, 12, 12),
            "summary",
            List.of("strength"),
            List.of(),
            "resume text"
        );
    }
}
