package interview.guide.modules.auth.mapper;

import interview.guide.modules.auth.dto.AdminUserResponse;
import interview.guide.modules.auth.dto.CurrentUserResponse;
import interview.guide.modules.auth.dto.IssuedInviteResponse;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.security.CurrentUser;
import interview.guide.modules.auth.service.IssuedInvite;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AuthResponseMapper {
  CurrentUser toCurrentUser(UserEntity user);
  CurrentUserResponse toResponse(CurrentUser user);
  AdminUserResponse toAdminResponse(UserEntity user);
  IssuedInviteResponse toInviteResponse(IssuedInvite invite);
}
