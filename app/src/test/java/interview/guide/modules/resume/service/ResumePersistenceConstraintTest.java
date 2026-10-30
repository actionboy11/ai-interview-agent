package interview.guide.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.mapper.ResumeMapper;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.resume.repository.ResumeAnalysisRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.sql.SQLException;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

@DisplayName("简历入库约束分类")
class ResumePersistenceConstraintTest {
  @Test
  @DisplayName("其他唯一约束即使 SQLSTATE 为 23505 也不是简历重复")
  void otherUniqueConstraintIsNotResumeDuplicate() {
    var repository = mock(ResumeRepository.class);
    var users = mock(UserRepository.class);
    var currentUser = mock(CurrentUserProvider.class);
    var ownerId = UUID.randomUUID();
    when(currentUser.requireUserId()).thenReturn(ownerId);
    when(users.getReferenceById(ownerId)).thenReturn(mock(UserEntity.class));
    var sql = new SQLException("unique violation", "23505");
    var constraint = new ConstraintViolationException("insert failed", sql, "resumes_pkey");
    when(repository.saveAndFlush(any())).thenThrow(
        new DataIntegrityViolationException("insert failed", new IllegalStateException(constraint)));
    var service = new ResumePersistenceService(repository, mock(ResumeAnalysisRepository.class),
        new ObjectMapper(), mock(ResumeMapper.class), new FileHashService(), currentUser, users,
        null, null);
    var file = new MockMultipartFile("file", "resume.txt", "text/plain", new byte[] {1});

    assertThatThrownBy(() -> service.saveResume(file, "text", "resumes/key", "url"))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.RESUME_UPLOAD_FAILED.getCode()));
  }
}
