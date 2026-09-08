package com.ctds.common.auth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ctds.auth 配置项（ADR-005 §3 第 7 项；全表见 docs/designs/WBS-2.4.5-hifi.md）。
 * 真实密钥只进配置中心/KMS，禁止写入代码与仓库（红线 7）。
 */
@ConfigurationProperties(prefix = "ctds.auth")
public class AuthProperties {

    /** 服务侧 RBAC 强制总开关；身份上下文读取不受其约束。 */
    private boolean enabled = true;

    private final Header header = new Header();

    /** 角色→逗号分隔权限清单（模式 A 配置文件来源；3.9.2 覆盖 RolePermissionMapper Bean 换库表）。 */
    private Map<String, String> permissions = new LinkedHashMap<>();

    private final Audit audit = new Audit();

    private final Gateway gateway = new Gateway();

    private final Jwt jwt = new Jwt();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean value) {
        this.enabled = value;
    }

    public Header getHeader() {
        return header;
    }

    public Map<String, String> getPermissions() {
        return permissions;
    }

    public void setPermissions(final Map<String, String> value) {
        this.permissions = value == null ? new LinkedHashMap<>() : value;
    }

    public Audit getAudit() {
        return audit;
    }

    public Gateway getGateway() {
        return gateway;
    }

    public Jwt getJwt() {
        return jwt;
    }

    /** 身份上下文头命名（可配置，默认沿 ADR-005 §3 第 7 项）。 */
    public static class Header {

        private String subject = "X-Ctds-Subject";
        private String roles = "X-Ctds-Roles";

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

    /** 拒绝/失败联动 2.4.4 审计（默认开启，可关）。 */
    public static class Audit {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(final boolean value) {
            this.enabled = value;
        }
    }

    /** 网关侧 JWT 校验（仅网关服务开启；V1.0 交付为组件，3.5.2 启用）。 */
    public static class Gateway {

        private boolean enabled;
        private List<String> permitPaths = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(final boolean value) {
            this.enabled = value;
        }

        public List<String> getPermitPaths() {
            return permitPaths;
        }

        public void setPermitPaths(final List<String> value) {
            this.permitPaths = value == null ? new ArrayList<>() : value;
        }
    }

    /** JWT 校验来源与声明映射（secret 与 jwk-set-uri 二选一）。 */
    public static class Jwt {

        private String secret;
        private String jwkSetUri;
        private String issuer;
        private String rolesClaim = "roles";

        public String getSecret() {
            return secret;
        }

        public void setSecret(final String value) {
            this.secret = value;
        }

        public String getJwkSetUri() {
            return jwkSetUri;
        }

        public void setJwkSetUri(final String value) {
            this.jwkSetUri = value;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(final String value) {
            this.issuer = value;
        }

        public String getRolesClaim() {
            return rolesClaim;
        }

        public void setRolesClaim(final String value) {
            this.rolesClaim = value;
        }
    }
}
