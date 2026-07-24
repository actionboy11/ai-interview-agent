package interview.guide.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.config.AppConfigProperties;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.resume.messaging.ResumeAnalysisTaskPublisher;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class ResumeUploadServiceMessagingTest {

    @Test
    void shouldPublishSavedResumeWithoutChangingUploadResponse() {
        ResumeParseService parseService = mock(ResumeParseService.class);
        FileStorageService storageService = mock(FileStorageService.class);
        ResumePersistenceService persistenceService = mock(ResumePersistenceService.class);
        ResumeAnalysisTaskPublisher publisher = mock(ResumeAnalysisTaskPublisher.class);
        MultipartFile file = mock(MultipartFile.class);

        when(file.getOriginalFilename()).thenReturn("resume.pdf");
        when(file.getSize()).thenReturn(100L);
        when(parseService.detectContentType(file)).thenReturn("application/pdf");
        when(parseService.parseResume(file)).thenReturn("resume text");
        when(persistenceService.findExistingResume(file)).thenReturn(Optional.empty());
        when(storageService.uploadResume(file)).thenReturn("resumes/resume.pdf");
        when(storageService.getFileUrl("resumes/resume.pdf")).thenReturn("http://files/resume.pdf");

        ResumeEntity saved = new ResumeEntity();
        saved.setId(42L);
        saved.setOriginalFilename("resume.pdf");
        when(persistenceService.saveResume(
            file,
            "resume text",
            "resumes/resume.pdf",
            "http://files/resume.pdf"
        )).thenReturn(saved);

        ResumeUploadService service = new ResumeUploadService(
            parseService,
            storageService,
            persistenceService,
            mock(AppConfigProperties.class),
            mock(FileValidationService.class),
            publisher,
            mock(ResumeRepository.class),
            mock(TransactionalExecutor.class)
        );

        Map<String, Object> result = service.uploadAndAnalyze(file);

        verify(publisher).publish(42L);
        @SuppressWarnings("unchecked")
        Map<String, Object> resume = (Map<String, Object>) result.get("resume");
        assertThat(resume)
            .containsEntry("id", 42L)
            .containsEntry("filename", "resume.pdf")
            .containsEntry("analyzeStatus", "PENDING");
    }
}
