package com.ctds.connector.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.MySQLContainer;

/**
 * 模块内共享 MySQL 8 容器（沿 catalog / contract / delivery 同名支撑类先例；ADR-010 形态 B）。
 * 各测试类使用独立库名；无 Docker 时由测试基类 {@code @Testcontainers(disabledWithoutDocker = true)} 跳过。
 */
public final class SharedMySqlContainer {

    private static final MySQLContainer<?> CONTAINER = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ctds_bootstrap");
    private static final Set<String> READY_DATABASES = ConcurrentHashMap.newKeySet();
    private static final String DATABASE_NAME_PATTERN = "[a-z0-9_]{1,64}";

    private SharedMySqlContainer() {
    }

    public static synchronized MySQLContainer<?> container() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }

    public static void register(final DynamicPropertyRegistry registry, final String database) {
        prepare(database);
        registry.add("spring.datasource.url", () -> jdbcUrlFor(database));
        registry.add("spring.datasource.username", () -> container().getUsername());
        registry.add("spring.datasource.password", () -> container().getPassword());
    }

    public static String jdbcUrlFor(final String database) {
        prepare(database);
        return container().getJdbcUrl().replace("/" + CONTAINER.getDatabaseName(), "/" + database);
    }

    private static void prepare(final String database) {
        if (!database.matches(DATABASE_NAME_PATTERN)) {
            throw new IllegalArgumentException("非法库名：" + database);
        }
        if (READY_DATABASES.contains(database)) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(
                container().getJdbcUrl(), "root", container().getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS `" + database + "`");
            statement.execute("GRANT ALL PRIVILEGES ON `" + database + "`.* TO '"
                    + applicationUsername() + "'@'%'");
        } catch (SQLException e) {
            throw new IllegalStateException("创建测试库失败：" + database, e);
        }
        READY_DATABASES.add(database);
    }

    private static String applicationUsername() {
        final String username = container().getUsername();
        if (!username.matches("[A-Za-z0-9_]{1,32}")) {
            throw new IllegalStateException("容器用户名形态非法：" + username);
        }
        return username;
    }
}
