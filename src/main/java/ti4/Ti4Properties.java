package ti4;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paths that deliberately live outside the jar, so card images and the objective
 * catalogue can change without a rebuild.
 */
@ConfigurationProperties(prefix = "ti4")
public record Ti4Properties(String imagesDir, String seedFile, String factionsFile) {
}
