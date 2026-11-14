package com.ctds.common.auth;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WBS-3.9.3 Q3-A：部署域声明式配置（`ctds.domain.*`，部署清单 env / 配置文件承载，Q10-A）。
 * 缺省 ACTIVE 零行为（既有演示链零影响）；仅 ACTIVE/RETIRED 合法，非法值启动失败（边界 5，
 * 不得静默当 ACTIVE）；生命周期登记三键（action/change-order/operator）要么全空要么全齐（边界 9）。
 */
@ConfigurationProperties(prefix = "ctds.domain")
public class DomainProperties {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_RETIRED = "RETIRED";

    /** 部署域标识（读面渲染；缺省不配置 = 读面返回空串占位）。 */
    private String id = "";

    /** 部署域名称（读面渲染）。 */
    private String name = "";

    /** 平台版本（S3-3 升级 = 此值递增 + 滚动更新）。 */
    private String platformVersion = "";

    /** 生命周期状态；仅 ACTIVE/RETIRED 合法，缺省 ACTIVE。 */
    private String status = STATUS_ACTIVE;

    /** 库 schema 清单（逗号分隔绑定；evidence 启动校验与读面渲染依据）。 */
    private List<String> schemaList = new ArrayList<>();

    /** 服务清单（逗号分隔绑定；读面渲染）。 */
    private List<String> serviceList = new ArrayList<>();

    /** 生命周期动作标记（OPEN/UPGRADE/RETIRE/RESTORE）；与 change-order/operator 三键齐备才生效。 */
    private String action = "";

    /** 变更单编号（B9-R3 登记义务）。 */
    private String changeOrder = "";

    /** 操作人（B9-R3 登记义务）。 */
    private String operator = "";

    /** 退役态豁免面（Q8-A：servlet 与网关同一配置键）。 */
    private final Retired retired = new Retired();

    public static class Retired {

        /** 退役态豁免路径模式（Ant 风格）；缺省仅健康读数。 */
        private List<String> permitPathPatterns = new ArrayList<>(List.of("/actuator/**"));

        public List<String> getPermitPathPatterns() {
            return permitPathPatterns;
        }

        public void setPermitPathPatterns(final List<String> value) {
            this.permitPathPatterns = value == null ? new ArrayList<>() : value;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(final String value) {
        this.id = blankToEmpty(value);
    }

    public String getName() {
        return name;
    }

    public void setName(final String value) {
        this.name = blankToEmpty(value);
    }

    public String getPlatformVersion() {
        return platformVersion;
    }

    public void setPlatformVersion(final String value) {
        this.platformVersion = blankToEmpty(value);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(final String value) {
        this.status = value == null || value.isBlank() ? STATUS_ACTIVE : value.trim();
    }

    public List<String> getSchemaList() {
        return schemaList;
    }

    public void setSchemaList(final List<String> value) {
        this.schemaList = value == null ? new ArrayList<>() : value;
    }

    public List<String> getServiceList() {
        return serviceList;
    }

    public void setServiceList(final List<String> value) {
        this.serviceList = value == null ? new ArrayList<>() : value;
    }

    public String getAction() {
        return action;
    }

    public void setAction(final String value) {
        this.action = blankToEmpty(value);
    }

    public String getChangeOrder() {
        return changeOrder;
    }

    public void setChangeOrder(final String value) {
        this.changeOrder = blankToEmpty(value);
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(final String value) {
        this.operator = blankToEmpty(value);
    }

    public Retired getRetired() {
        return retired;
    }

    /** 是否退役态（大小写不敏感；非法值不在此判定，validateStatus 启动期拦截）。 */
    public boolean isRetired() {
        return STATUS_RETIRED.equalsIgnoreCase(status);
    }

    /** 状态值域校验（启动期调用；非法值 fail-closed，边界 5）。 */
    public void validateStatus() {
        if (!STATUS_ACTIVE.equalsIgnoreCase(status) && !STATUS_RETIRED.equalsIgnoreCase(status)) {
            throw new IllegalArgumentException(
                    "ctds.domain.status 非法值 " + status + "（仅 ACTIVE/RETIRED 合法，不得静默降级）");
        }
    }

    /** 生命周期登记三键要么全空要么全齐（B9-R3；部分填写 = 启动失败，边界 9）。 */
    public boolean hasCompleteLifecycleRegistration() {
        boolean anySet = isSet(action) || isSet(changeOrder) || isSet(operator);
        boolean allSet = isSet(action) && isSet(changeOrder) && isSet(operator);
        return !anySet || allSet;
    }

    private static boolean isSet(final String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToEmpty(final String value) {
        return value == null ? "" : value.trim();
    }
}
