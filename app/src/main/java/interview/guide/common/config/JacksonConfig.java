package interview.guide.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson Configuration
 * Jackson配置
 */
@Configuration
public class JacksonConfig {

    /**
     * Create and configure an ObjectMapper bean.
     * 创建并配置ObjectMapper bean。
     *
     * @return the configured ObjectMapper
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
