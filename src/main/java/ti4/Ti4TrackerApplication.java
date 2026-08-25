package ti4;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(Ti4Properties.class)
public class Ti4TrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(Ti4TrackerApplication.class, args);
    }
}
