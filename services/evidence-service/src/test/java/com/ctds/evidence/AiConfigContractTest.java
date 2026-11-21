package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * WBS-3.10.4 契约锚（hifi §2 / §7 T 静态面；沿 RbacGovernanceContractTest 先例）：
 * V6 迁移结构（三表 + 密钥引用占位 + 权限增量 seed）、4 端点与双权限点挂载、
 * 服务事务与判定复用、错误码 1014 段封闭（分域口径，C-10.1-10.2 :236）。
 * 本类零 Docker、零网络，CI 可跑（承载既有面零改动与结构红线）。
 */
class AiConfigContractTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent().getParent();
    private static final Path SERVICE = ROOT.resolve("services/evidence-service");
    private static final Path MAIN = SERVICE.resolve("src/main/java/com/ctds/evidence");

    @Test
    void v6MigrationCreatesThreeTablesWithKeyRefPlaceholder() throws IOException {
        Path migration = SERVICE.resolve("src/main/resources/db/migration/V6__create_ai_gateway_config.sql");
        assertThat(migration).as("Q2-A/Q3-A：配置面须由 evidence-service V6 迁移承载").exists();
        String sql = Files.readString(migration).toUpperCase();
        assertThat(sql).contains("CREATE TABLE AI_GATEWAY_CONFIG");
        assertThat(sql).contains("CREATE TABLE AI_GATEWAY_PROVIDER_CONFIG");
        assertThat(sql).contains("CREATE TABLE AI_GATEWAY_CONFIG_CHANGE_LOG");
        assertThat(sql).contains("API_KEY_REF");
        assertThat(sql).contains("TOKEN_BUDGET");
        assertThat(sql).contains("DEFAULT_PROVIDER");
        // 红线留痕：密钥值禁止入库（ADR-003），api_key_ref 仅引用占位（hifi §2.2）
        assertThat(Files.readString(migration)).contains("禁止入库");
        // seed：总控单例行 + 封闭清单四行 + 权限两点（platform.sysadmin 专属）
        assertThat(sql).contains("INSERT INTO AI_GATEWAY_CONFIG");
        assertThat(sql).contains("INSERT INTO AI_GATEWAY_PROVIDER_CONFIG");
        assertThat(sql).contains("'GLM'").contains("'DEEPSEEK'").contains("'QWEN'").contains("'KIMI'");
        assertThat(sql).contains("PLATFORM.AI.CONFIG.READ");
        assertThat(sql).contains("PLATFORM.AI.CONFIG.WRITE");
        assertThat(sql).contains("RCW3104");
    }

    @Test
    void controllerExposesFourEndpointsWithTwoPermissionPoints() throws IOException {
        Path controller = MAIN.resolve("interfaces/AiConfigController.java");
        assertThat(controller).as("4 端点须落在系统管理域既有落位（evidence-service）").exists();
        String text = Files.readString(controller);
        assertThat(text).contains("@GetMapping");
        // 写面 POST 沿仓内写面惯例（EvidenceArchitectureTest 接口层守卫禁 Put/Patch/Delete 改删动词）
        assertThat(text).contains("@PostMapping");
        assertThat(text).contains("/save");
        assertThat(text).as("独立命名空间（不占用 /api/v1/platform 封闭面）")
                .contains("RequestMapping(\"/api/v1/ai-config\")");
        assertThat(text).contains("/preview");
        assertThat(text).contains("/changes");
        assertThat(text).contains("@RequirePermission(\"platform.ai.config.read\")");
        assertThat(text).contains("@RequirePermission(\"platform.ai.config.write\")");
    }

    @Test
    void serviceIsTransactionalAndReusesGatewayControl() throws IOException {
        Path service = MAIN.resolve("application/AiConfigService.java");
        assertThat(service).exists();
        String text = Files.readString(service);
        assertThat(text).contains("@Transactional");
        // 判定预览复用 3.10.1 纯函数（同语义零外呼，规格 B3-1）
        assertThat(text).contains("GatewayControl.check");
        assertThat(text).contains("ModelGatewayProperties");
    }

    @Test
    void storeLayeringFollowsRbacPrecedent() throws IOException {
        assertThat(MAIN.resolve("domain/AiConfigStore.java")).as("领域接口沿 RbacStore 先例").exists();
        assertThat(MAIN.resolve("infrastructure/JdbcAiConfigStore.java")).as("JDBC 实现沿 JdbcRbacStore 先例").exists();
    }

    @Test
    void errorCodesUseReserved1014SegmentOnly() throws IOException {
        Path codes = MAIN.resolve("domain/AiConfigErrorCodes.java");
        assertThat(codes).as("Q6-A：1014 段（3.10/AI 域）最小码值集须落定").exists();
        String text = Files.readString(codes);
        assertThat(text).contains("1014C0001");
        assertThat(text).as("本卡码值仅落 1014 段（不混入他域段）").doesNotContain("1013");
        assertThat(text).as("拒绝原因文案可区分（规格 B2-2）").contains("默认供应商");
        assertThat(text).contains("http(s)");
    }
}
