package com.ctds.gateway.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关配置项（前缀 ctds.gateway；全表见 docs/designs/WBS-3.5.2-hifi.md §1 / §3 / §5）。
 *
 * <p>路由为静态配置（不引入服务发现，登记演进项）；交付面为配置化白名单（默认空 = fail-closed）；
 * 限流阈值可配置（进程内令牌桶）；判定服务地址与超时配置化（超时默认 2s，ADR-020 §2.8 授权消费方定）。
 * 密钥/令牌一律环境变量注入（红线 7），本类不含任何密钥字段。</p>
 */
@ConfigurationProperties(prefix = "ctds.gateway")
public class GatewayProperties {

    /** 静态路由表（本卡仅支持 {@code Path=} 谓词与空过滤器列表，其余形态启动即失败，见 GatewayRouteConfig）。 */
    private List<Route> routes = new ArrayList<>();

    private final Delivery delivery = new Delivery();

    private final RateLimit rateLimit = new RateLimit();

    private final Policy policy = new Policy();

    public List<Route> getRoutes() {
        return routes;
    }

    public void setRoutes(final List<Route> value) {
        this.routes = value == null ? new ArrayList<>() : value;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Policy getPolicy() {
        return policy;
    }

    /** 静态路由定义（id / uri / predicates / filters / delivery-face）。 */
    public static class Route {

        private String id;
        private String uri;
        private List<String> predicates = new ArrayList<>();
        private List<String> filters = new ArrayList<>();
        /**
         * 交付面路由标记（WBS-3.6.8 W1 前置实测勘误：交付链过滤器族此前隐含"全部路由 = 交付面"，
         * V1.0 单路由下从未暴露；六门面普通代理路由上线后须显式区分——标记路由继续受白名单
         * fail-closed / 合约头强制 / 判定 / 计量约束（语义零改动），未标记路由 = 普通代理
         * （仅鉴权链 + 限流，判定语义归各下游服务）。
         */
        private boolean deliveryFace;

        public String getId() {
            return id;
        }

        public void setId(final String value) {
            this.id = value;
        }

        public String getUri() {
            return uri;
        }

        public void setUri(final String value) {
            this.uri = value;
        }

        public List<String> getPredicates() {
            return predicates;
        }

        public void setPredicates(final List<String> value) {
            this.predicates = value == null ? new ArrayList<>() : value;
        }

        public List<String> getFilters() {
            return filters;
        }

        public void setFilters(final List<String> value) {
            this.filters = value == null ? new ArrayList<>() : value;
        }

        public boolean isDeliveryFace() {
            return deliveryFace;
        }

        public void setDeliveryFace(final boolean value) {
            this.deliveryFace = value;
        }
    }

    /** 交付调用面（hifi §5）：白名单 routeId 集合 + 凭据与判定要素头名（头名可配置）。 */
    public static class Delivery {

        /** 交付面白名单（routeId 显式列举；默认空 = 无交付面可用，fail-closed）。 */
        private List<String> routes = new ArrayList<>();
        private String contractNoHeader = "X-Ctds-Contract-No";
        private String purposeHeader = "X-Ctds-Purpose";
        private String territoryHeader = "X-Ctds-Territory";

        public List<String> getRoutes() {
            return routes;
        }

        public void setRoutes(final List<String> value) {
            this.routes = value == null ? new ArrayList<>() : value;
        }

        public String getContractNoHeader() {
            return contractNoHeader;
        }

        public void setContractNoHeader(final String value) {
            this.contractNoHeader = value;
        }

        public String getPurposeHeader() {
            return purposeHeader;
        }

        public void setPurposeHeader(final String value) {
            this.purposeHeader = value;
        }

        public String getTerritoryHeader() {
            return territoryHeader;
        }

        public void setTerritoryHeader(final String value) {
            this.territoryHeader = value;
        }
    }

    /** 限流（hifi §3）：进程内令牌桶，维度 = 调用主体 + 接口（routeId + 方法 + 路径模板）。 */
    public static class RateLimit {

        private boolean enabled = true;
        private int defaultCapacity = 100;
        private int defaultRefillPerSecond = 100;
        private Map<String, Bucket> routes = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(final boolean value) {
            this.enabled = value;
        }

        public int getDefaultCapacity() {
            return defaultCapacity;
        }

        public void setDefaultCapacity(final int value) {
            this.defaultCapacity = value;
        }

        public int getDefaultRefillPerSecond() {
            return defaultRefillPerSecond;
        }

        public void setDefaultRefillPerSecond(final int value) {
            this.defaultRefillPerSecond = value;
        }

        public Map<String, Bucket> getRoutes() {
            return routes;
        }

        public void setRoutes(final Map<String, Bucket> value) {
            this.routes = value == null ? new LinkedHashMap<>() : value;
        }

        /** 单路由阈值覆盖（缺省回落 default-*）。 */
        public static class Bucket {

            private Integer capacity;
            private Integer refillPerSecond;

            public Integer getCapacity() {
                return capacity;
            }

            public void setCapacity(final Integer value) {
                this.capacity = value;
            }

            public Integer getRefillPerSecond() {
                return refillPerSecond;
            }

            public void setRefillPerSecond(final Integer value) {
                this.refillPerSecond = value;
            }
        }
    }

    /** 策略执行点接入（hifi §4.2）：合约域内部判定端点地址 / 超时 / 服务身份头。 */
    public static class Policy {

        private String baseUrl = "";
        private String path = "/api/v1/contracts/internal/usage-checks";
        private Duration timeout = Duration.ofSeconds(2);
        private String subjectHeader = "X-Ctds-Subject";
        private String rolesHeader = "X-Ctds-Roles";
        private String subject = "gateway-service";
        private String roles = "gateway-internal";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(final String value) {
            this.baseUrl = value == null ? "" : value;
        }

        public String getPath() {
            return path;
        }

        public void setPath(final String value) {
            this.path = value;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(final Duration value) {
            this.timeout = value;
        }

        public String getSubjectHeader() {
            return subjectHeader;
        }

        public void setSubjectHeader(final String value) {
            this.subjectHeader = value;
        }

        public String getRolesHeader() {
            return rolesHeader;
        }

        public void setRolesHeader(final String value) {
            this.rolesHeader = value;
        }

        public String getSubject() {
            return subject;
        }

        public void setSubject(final String value) {
            this.subject = value;
        }

        public String getRoles() {
            return roles;
        }

        public void setRoles(final String value) {
            this.roles = value;
        }
    }
}
