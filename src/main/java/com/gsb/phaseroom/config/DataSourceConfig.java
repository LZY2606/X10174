package com.gsb.phaseroom.config;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;

@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(AppProperties props) throws Exception {
        Path db = Path.of(props.getDatabase()).toAbsolutePath().normalize();
        if (db.getParent() != null) {
            Files.createDirectories(db.getParent());
        }
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db + "?foreign_keys=on");
        ds.setEnforceForeignKeys(true);
        ds.setBusyTimeout(5000);
        return ds;
    }
}
