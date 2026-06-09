package com.example.contacts.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Application clock (CD-028). Production uses the system UTC clock; tests can
 * register their own {@link Clock} bean (or a fixed/offset clock) to cross
 * time windows — refresh-token expiry, reuse grace — without sleeping.
 */
@Configuration
public class TimeConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
