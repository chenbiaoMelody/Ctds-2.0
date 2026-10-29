package com.ctds.connector.sdk.adapter.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * JDBC URL 解析与参数白名单（WBS-3.6.6 hifi §9 U2）：只接受 {@code jdbc:mysql://} 形态，
 * host / port / schema 提取与危险参数拒绝（白名单外一律不可用）。
 */
class JdbcUrlPolicyTest {

    @Test
    void parsesHostPortAndSchema() {
        final Optional<JdbcUrlPolicy.Target> target =
                JdbcUrlPolicy.parse("jdbc:mysql://db.example.com:3307/dataset");

        assertThat(target).isPresent();
        assertThat(target.orElseThrow().host()).isEqualTo("db.example.com");
        assertThat(target.orElseThrow().port()).isEqualTo(3307);
        assertThat(target.orElseThrow().schema()).isEqualTo("dataset");
    }

    @Test
    void defaultsPortTo3306() {
        assertThat(JdbcUrlPolicy.parse("jdbc:mysql://db.example.com/dataset").orElseThrow().port())
                .isEqualTo(3306);
    }

    @Test
    void parsesIpv6HostWithBrackets() {
        final JdbcUrlPolicy.Target target =
                JdbcUrlPolicy.parse("jdbc:mysql://[2001:db8::1]:3306/dataset").orElseThrow();

        assertThat(target.host()).isEqualTo("2001:db8::1");
        assertThat(target.schema()).isEqualTo("dataset");
    }

    @Test
    void allowsWhitelistedParametersCaseInsensitively() {
        final Optional<JdbcUrlPolicy.Target> target = JdbcUrlPolicy.parse(
                "jdbc:mysql://db.example.com:3306/dataset?SSLMode=REQUIRED&serverTimezone=Asia/Shanghai"
                        + "&connectTimeout=3000&characterEncoding=utf8");

        assertThat(target).isPresent();
        assertThat(target.orElseThrow().schema()).isEqualTo("dataset");
    }

    @Test
    void rejectsDangerousLocalInfileParameter() {
        assertThat(JdbcUrlPolicy.parse(
                "jdbc:mysql://db.example.com/dataset?allowLoadLocalInfile=true")).isEmpty();
        assertThat(JdbcUrlPolicy.parse(
                "jdbc:mysql://db.example.com/dataset?allowUrlInLocalInfile=true")).isEmpty();
    }

    @Test
    void rejectsDangerousDeserializeAndMultiQueryParameters() {
        assertThat(JdbcUrlPolicy.parse(
                "jdbc:mysql://db.example.com/dataset?autoDeserialize=true")).isEmpty();
        assertThat(JdbcUrlPolicy.parse(
                "jdbc:mysql://db.example.com/dataset?allowMultiQueries=true")).isEmpty();
    }

    @Test
    void rejectsUnknownParameter() {
        assertThat(JdbcUrlPolicy.parse("jdbc:mysql://db.example.com/dataset?useCursorFetch=true"))
                .isEmpty();
    }

    @Test
    void rejectsMissingSchema() {
        assertThat(JdbcUrlPolicy.parse("jdbc:mysql://db.example.com")).isEmpty();
        assertThat(JdbcUrlPolicy.parse("jdbc:mysql://db.example.com/")).isEmpty();
    }

    @Test
    void rejectsEmptyHostAndForeignScheme() {
        assertThat(JdbcUrlPolicy.parse("jdbc:mysql:///dataset")).isEmpty();
        assertThat(JdbcUrlPolicy.parse("https://db.example.com/dataset")).isEmpty();
        assertThat(JdbcUrlPolicy.parse("jdbc:mysql://user:pass@db.example.com/dataset")).isEmpty();
    }
}
