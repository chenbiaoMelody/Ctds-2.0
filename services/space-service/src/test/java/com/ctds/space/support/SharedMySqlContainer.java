package com.ctds.space.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.MySQLContainer;

/**
 * DB-25 集成测试共享容器（ADR-010 §8 容器声明形态 B）：模块内 JVM 级单例 MySQL 容器——
 * 首次访问启动、进程退出由 Testcontainers Ryuk 回收（官方 Singleton containers 模式）；
 * 各测试类经 {@link #register(DynamicPropertyRegistry, String)} 使用<b>独立库名</b>。
 * 沿 subject-service 同名支撑类先例（建库/GRANT 支撑测试逻辑多副本收敛候选，ADR-010 §10 登记）。
 * 无 Docker 时不启动：各测试类保留 {@code @Testcontainers(disabledWithoutDocker = true)}。
 */
public final class SharedMySqlContainer {

    /** 模块内唯一容器实例；显式镜像标签 mysql:8.0（ADR-010 §3.2 禁止 latest）。 */
    private static final MySQLContainer<?> CONTAINER = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ctds_bootstrap");

    /** 已建库名（幂等，避免逐次 DDL）。 */
    private static final Set<String> READY_DATABASES = ConcurrentHashMap.newKeySet();

    /** 库名白名单：库名由测试类硬编码，仍拒非法字符防 DDL 拼接注入。 */
    private static final String DATABASE_NAME_PATTERN = "[a-z0-9_]{1,64}";

    private SharedMySqlContainer() {
    }

    /** 共享容器（首次调用启动；后续调用返回同一实例）。 */
    public static synchronized MySQLContainer<?> container() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }

    /** 把共享容器的连接参数（指定库名）注入测试上下文；库不存在则创建。 */
    public static void register(final DynamicPropertyRegistry registry, final String database) {
        prepare(database);
        registry.add("spring.datasource.url", () -> jdbcUrlFor(database));
        registry.add("spring.datasource.username", () -> container().getUsername());
        registry.add("spring.datasource.password", () -> container().getPassword());
    }

    /** 指定库名的 JDBC URL（库不存在则创建）；不含 Spring 上下文的纯 JUnit 用例直接使用。 */
    public static String jdbcUrlFor(final String database) {
        prepare(database);
        // 容器 URL 形如 jdbc:mysql://host:port/<默认库>[?参数]——只替换库名段，连接参数逐字保留
        return container().getJdbcUrl().replace("/" + CONTAINER.getDatabaseName(), "/" + database);
    }

    private static void prepare(final String database) {
        if (!database.matches(DATABASE_NAME_PATTERN)) {
            throw new IllegalArgumentException("非法库名（仅允许小写字母/数字/下划线）：" + database);
        }
        if (READY_DATABASES.contains(database)) {
            return;
        }
        // 建库与授权用容器 root（Testcontainers MySQLContainer：MYSQL_ROOT_PASSWORD == 应用用户口令）；
        // 测试自身仍以应用用户接入（最小权限），故须把新库授权给该用户。
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
