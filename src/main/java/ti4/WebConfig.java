package ti4;

import java.nio.file.Path;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves card scans straight off disk rather than from the classpath, so dropping
 * a new image into data/images makes it available without repackaging.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final Ti4Properties properties;

    public WebConfig(Ti4Properties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(properties.imagesDir()).toAbsolutePath().toUri().toString();
        registry.addResourceHandler("/images/**").addResourceLocations(location);
    }
}
