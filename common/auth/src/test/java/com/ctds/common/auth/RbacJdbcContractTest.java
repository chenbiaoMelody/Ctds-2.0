package com.ctds.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * WBS-3.9.2 T3 / T8 / T9：库表映射只读、fail-closed、单一 Bean、判定内核零改动。
 * 依据 hifi §1 B1-R3 / §4 / §5 T8·T9 与 §9 E4·E5 勘误；结构断言先于实现成红。
 */
class RbacJdbcContractTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent().getParent();
    private static final Path AUTH = ROOT.resolve("common/auth/src/main/java/com/ctds/common/auth");

    @Test
    void jdbcMapperExistsAndIsReadOnly() throws IOException {
        Path source = AUTH.resolve("JdbcRolePermissionMapper.java");
        assertThat(Files.exists(source)).as("Q1-A：JdbcRolePermissionMapper 须存在").isTrue();
        String text = Files.readString(source);
        assertThat(text).contains("implements RolePermissionMapper");
        assertThat(text).contains("SELECT");
        assertThat(text).doesNotContain("INSERT ", "UPDATE ", "DELETE ");
        assertThat(text).contains("Set.of()");
    }

    @Test
    void jdbcAutoConfigurationIsRegisteredBeforeDefaultMapper() throws IOException {
        Path imports = ROOT.resolve("common/auth/src/main/resources/META-INF/spring/"
                + "org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(Files.readString(imports))
                .contains("com.ctds.common.auth.RbacJdbcAutoConfiguration");
        Path config = AUTH.resolve("RbacJdbcAutoConfiguration.java");
        assertThat(Files.exists(config)).isTrue();
        String text = Files.readString(config);
        assertThat(text).contains("AutoConfigureBefore(AuthAutoConfiguration.class)");
        assertThat(text).contains("ConditionalOnBean(JdbcTemplate.class)");
        assertThat(text).contains("@ConditionalOnMissingBean");
    }

    @Test
    void springJdbcIsDeclaredAsBootBomComponent() throws IOException {
        String pom = Files.readString(ROOT.resolve("common/auth/pom.xml"));
        assertThat(pom).contains("<artifactId>spring-jdbc</artifactId>");
    }

    @Test
    void authPropertiesCarriesRbacSchemaPrefix() throws IOException {
        String props = Files.readString(AUTH.resolve("AuthProperties.java"));
        assertThat(props).contains("class Rbac");
        assertThat(props).contains("getRbac()");
        assertThat(props).contains("schema");
    }

    @Test
    void decisionKernelIsUntouched() throws IOException {
        assertThat(Files.readString(AUTH.resolve("DefaultAccessControl.java")))
                .contains("mapper.permissionsOf(role)");
        assertThat(Files.readString(AUTH.resolve("PermissionInterceptor.java")))
                .contains("accessControl.require(permission)");
        assertThat(Files.readString(AUTH.resolve("AuthAutoConfiguration.java")))
                .contains("new ConfigRolePermissionMapper(properties.getPermissions())");
    }
}
