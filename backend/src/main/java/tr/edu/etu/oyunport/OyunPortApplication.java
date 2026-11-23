package tr.edu.etu.oyunport;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OyunPortApplication {
    public static void main(String[] args) {
        SpringApplication.run(OyunPortApplication.class, args);
    }
}
