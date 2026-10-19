package interview.guide.modules.auth.service;

import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.repository.RegistrationInviteRepository;
import interview.guide.modules.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(classes = AuthServiceTestSupport.TestApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:auth-services;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class AuthServiceTestSupport {
  @Configuration(proxyBeanMethods = false)
  @EntityScan(basePackageClasses = UserEntity.class)
  @EnableJpaRepositories(basePackageClasses = UserRepository.class)
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      TransactionAutoConfiguration.class})
  @Import({UsernameNormalizer.class, AdminAccountService.class, AuthService.class,
      InviteCodeService.class})
  static class TestApplication {
  }

  @Autowired UserRepository users;
  @Autowired RegistrationInviteRepository invites;
  @Autowired AdminAccountService accounts;
  @Autowired AuthService auth;
  @Autowired InviteCodeService inviteCodes;

  @BeforeEach
  void cleanDatabase() {
    invites.deleteAllInBatch();
    users.deleteAllInBatch();
  }

  UserEntity createUser(String username, UserRole role) {
    return accounts.createUser(new CreateAdminUserCommand(username, "secret-password",
        username + " display", role));
  }
}
