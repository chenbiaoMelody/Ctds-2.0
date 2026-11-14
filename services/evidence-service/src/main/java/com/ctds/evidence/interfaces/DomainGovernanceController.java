package com.ctds.evidence.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.DomainErrorCodes;
import com.ctds.common.auth.DomainProperties;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.errorcode.BizException;
import com.ctds.evidence.application.DomainView;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WBS-3.9.3 Q1-A：部署域信息只读读面（B9-1，平台管理读面既有落点 = evidence-service）。
 * 唯一新增端点 `GET /api/v1/platform/domain`，显式挂 `platform.domain.read`（B9-R2/B3-R4，
 * 仅 platform.sysadmin 持有，V4 授予）；每次请求计算配置校验结论（B9-1）；退役态本端点被
 * 豁免模式放行（evidence 增配 `/api/v1/platform/**`），照常返回当前状态（S3-5 读面复核）。
 * 防御性运行期分支：声明清单与实际 schema 不一致 → 503 + `1013S0002`（正常情况下启动校验
 * 已 fail-closed 拦截，此分支覆盖"清单后改"窗口）。
 */
@RestController
@RequestMapping("/api/v1/platform")
public class DomainGovernanceController {

    private final DomainProperties properties;
    private final ObjectProvider<DataSource> dataSourceProvider;

    public DomainGovernanceController(final DomainProperties properties,
            final ObjectProvider<DataSource> dataSourceProvider) {
        this.properties = properties;
        this.dataSourceProvider = dataSourceProvider;
    }

    @GetMapping(path = "/domain", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("platform.domain.read")
    public ApiResult<DomainView> domain() {
        final String lifecycleStatus = properties.isRetired()
                ? "RETIRED" : "ACTIVE";
        return ApiResult.ok(new DomainView(
                properties.getId(),
                properties.getName(),
                properties.getPlatformVersion(),
                lifecycleStatus,
                List.copyOf(properties.getSchemaList()),
                List.copyOf(properties.getServiceList()),
                consistencyVerdict(),
                DomainView.SCOPE_NOTE));
    }

    /** 配置校验结论（B9-1）：未配清单 / 无数据源 = NOT_APPLICABLE（诚实标注，不显示"正常"）。 */
    private String consistencyVerdict() {
        final List<String> schemaList = properties.getSchemaList();
        if (schemaList == null || schemaList.isEmpty()) {
            return DomainView.CONSISTENCY_NOT_APPLICABLE;
        }
        final DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return DomainView.CONSISTENCY_NOT_APPLICABLE;
        }
        final String actual = actualSchema(dataSource);
        if (!schemaList.contains(actual)) {
            throw new BizException(DomainErrorCodes.CONFIG_MISMATCH,
                    DomainErrorCodes.CONFIG_MISMATCH_MESSAGE);
        }
        return DomainView.CONSISTENCY_CONSISTENT;
    }

    private String actualSchema(final DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            final String catalog = connection.getCatalog();
            if (catalog != null && !catalog.isBlank()) {
                return catalog;
            }
            final String schema = connection.getSchema();
            return schema == null ? "" : schema.toLowerCase(Locale.ROOT);
        } catch (final SQLException e) {
            throw new BizException(DomainErrorCodes.CONFIG_MISMATCH,
                    DomainErrorCodes.CONFIG_MISMATCH_MESSAGE);
        }
    }
}
