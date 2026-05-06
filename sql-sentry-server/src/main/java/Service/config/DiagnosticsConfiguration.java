package Service.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        AiProperties.class,
        CacheProperties.class,
        DiagnosticThreadPoolProperties.class,
        SqlRewriteProperties.class
})
public class DiagnosticsConfiguration {
}
