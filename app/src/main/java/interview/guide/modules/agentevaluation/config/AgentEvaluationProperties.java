package interview.guide.modules.agentevaluation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "app.agent-evaluation")
public class AgentEvaluationProperties {

  private boolean enabled = false;
  private String artifactKey = "";
  private Duration rawArtifactRetention = Duration.ofDays(30);
}
