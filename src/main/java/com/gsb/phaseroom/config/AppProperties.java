package com.gsb.phaseroom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "phaseroom")
public class AppProperties {

    /** Path to the SQLite database file. */
    private String database = "data/phaseroom.db";

    /** Residual acceptance window in seconds for model comparison. */
    private double windowSeconds = 5.0;

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public double getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(double windowSeconds) {
        this.windowSeconds = windowSeconds;
    }
}
