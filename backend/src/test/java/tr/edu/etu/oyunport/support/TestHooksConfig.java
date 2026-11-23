package tr.edu.etu.oyunport.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestHooksConfig {

    @Bean
    public ArmableCheckoutHooks armableCheckoutHooks() {
        return new ArmableCheckoutHooks();
    }
}
