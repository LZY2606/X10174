package com.seisjury.domain;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {
    @Bean
    public Clock systemClock() {
        return System::currentTimeMillis;
    }
}
