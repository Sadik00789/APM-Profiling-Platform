package com.apm.collector.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class ClickHouseConfig {

    @Value("${apm.clickhouse.url:jdbc:ch://localhost:8123/default}")
    private String clickhouseUrl;

    @Value("${apm.clickhouse.user:default}")
    private String user;

    @Value("${apm.clickhouse.password:}")
    private String password;

    @Value("${apm.clickhouse.pool.max-size:32}")
    private int maxPoolSize;

    @Value("${apm.clickhouse.pool.min-idle:8}")
    private int minIdle;

    @Value("${apm.clickhouse.pool.idle-timeout-ms:30000}")
    private long idleTimeoutMs;

    @Value("${apm.clickhouse.pool.max-lifetime-ms:600000}")
    private long maxLifetimeMs;

    @Value("${apm.clickhouse.pool.connection-timeout-ms:10000}")
    private long connectionTimeoutMs;

    @Bean
    public DataSource clickHouseDataSource() {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        config.setJdbcUrl(clickhouseUrl);
        config.setUsername(user != null ? user : "default");
        config.setPassword(password != null ? password : "");
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setIdleTimeout(idleTimeoutMs);
        config.setMaxLifetime(maxLifetimeMs);
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setPoolName("ClickHouseHikariPool");
        config.setConnectionTestQuery("SELECT 1");

        // ClickHouse specific connection properties
        config.addDataSourceProperty("compress", "1");
        config.addDataSourceProperty("decompress", "1");
        config.addDataSourceProperty("async_insert", "1");
        config.addDataSourceProperty("wait_for_async_insert", "0");
        config.addDataSourceProperty("socket_timeout", "30000");

        return new HikariDataSource(config);
    }
}
