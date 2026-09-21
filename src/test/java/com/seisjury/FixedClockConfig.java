package com.seisjury;

import com.seisjury.domain.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class FixedClockConfig {
    public static final long FIXED_NOW = 1_700_100_000_000L;

    @Bean
    @Primary
    public Clock fixedClock() {
        return () -> FIXED_NOW;
    }
}
