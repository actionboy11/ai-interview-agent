package interview.guide.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CORS跨域配置
 */
@Configuration
public class CorsConfig {

    private final CorsProperties corsProperties;

    public CorsConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 1. 获取配置字符串
        String allowedOrigins = corsProperties.getAllowedOrigins();
        // 2. 解析并添加到配置对象  ForEach循环遍历每个允许的来源，并将其添加到CORS配置中
        Arrays.stream(allowedOrigins.split(","))
              .map(String::trim)
              .forEach(config::addAllowedOrigin);

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        //setAllowCredentials(true)表示允许跨域请求携带凭证（如Cookie、HTTP认证信息等），
        // 这对于需要身份验证的请求是必要的。
        config.setAllowCredentials(true);
        // setMaxAge(3600L)表示预检请求的缓存时间为3600秒（1小时），在此期间，浏览器不会再次发送预检请求，从而提高性能。
        config.setMaxAge(3600L);

        // 3. 创建UrlBasedCorsConfigurationSource对象，并将CORS配置注册到指定的路径模式（这里是"/api/**"），
        // 表示对所有以"/api/"开头的请求应用该CORS配置。
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
