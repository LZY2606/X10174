package com.seisjury.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Seq {
    private final JdbcTemplate jdbc;

    public Seq(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long next(String name) {
        int inserted = jdbc.update("INSERT OR IGNORE INTO seq(name, next) VALUES(?, 1)", name);
        if (inserted > 0) {
            return 1L;
        }
        Integer current = jdbc.queryForObject("SELECT next FROM seq WHERE name = ?", Integer.class, name);
        long value = current == null ? 1L : current.longValue();
        jdbc.update("UPDATE seq SET next = ? WHERE name = ?", value + 1L, name);
        return value;
    }
}
