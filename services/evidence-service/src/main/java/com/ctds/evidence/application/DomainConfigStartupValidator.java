package com.ctds.evidence.application;

import com.ctds.common.auth.DomainProperties;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * WBS-3.9.3 Q6-A（B9-R4 fail-closed 最强口径）：持有读面的服务启动时校验自身数据源 schema
 * ∈ `ctds.domain.schema-list` 声明清单，不一致 → 启动失败（S3-4 演示路径）；未配清单或无数据源
 * → 校验跳过（边界 7，读面诚实标注 NOT_APPLICABLE）。全量 schema/服务清单一致性由部署脚本
 * 承载（B9-R4 部署期口径，scripts/deploy/domain-consistency.ps1）。
 */
@Component
public class DomainConfigStartupValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DomainConfigStartupValidator.class);

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final DomainProperties properties;

    public DomainConfigStartupValidator(final ObjectProvider<DataSource> dataSourceProvider,
            final DomainProperties properties) {
        this.dataSourceProvider = dataSourceProvider;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        final List<String> schemaList = properties.getSchemaList();
        if (schemaList == null || schemaList.isEmpty()) {
            return;
        }
        final DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return;
        }
        final String actual = actualSchema(dataSource);
        if (actual == null || actual.isBlank()) {
            return;
        }
        if (!schemaList.contains(actual)) {
            throw new IllegalStateException("ctds.domain.schema-list 配置不一致：实际数据源 schema "
                    + actual + " 不在声明清单 " + schemaList + " 中（B9-R4 fail-closed，拒绝以"
                    + "不一致状态启动；请修正清单或数据源后重试）");
        }
        log.info("domain config consistency verified: schema {} in declared list", actual);
    }

    private String actualSchema(final DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getCatalog() != null && !connection.getCatalog().isBlank()
                    ? connection.getCatalog() : connection.getSchema();
        } catch (final SQLException e) {
            throw new IllegalStateException("ctds.domain 启动校验无法读取实际数据源 schema"
                    + "（fail-closed，不假装正常）", e);
        }
    }
}
