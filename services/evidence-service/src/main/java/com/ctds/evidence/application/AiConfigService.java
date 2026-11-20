package com.ctds.evidence.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.modelgateway.config.ModelGatewayProperties;
import com.ctds.common.modelgateway.control.ControlDecision;
import com.ctds.common.modelgateway.control.GatewayControl;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.evidence.domain.AiConfigChangeView;
import com.ctds.evidence.domain.AiConfigErrorCodes;
import com.ctds.evidence.domain.AiConfigStore;
import com.ctds.evidence.domain.AiConfigStore.ChangeEntry;
import com.ctds.evidence.domain.AiConfigStore.GatewayState;
import com.ctds.evidence.domain.AiConfigStore.ProviderState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WBS-3.10.4 应用服务（hifi §2.3）：合并视图组装 / 全量保存与校验（拒绝原因可区分，规格 B2-2）/
 * 逐字段留痕同事务（规格 B2-3）/ 判定预览复用 3.10.1 纯函数 {@link GatewayControl#check}
 * （先开关后预算；V1.0 预览按已用量=0 执行，零外呼零落库，规格 B3）。
 * 诚实边界：V1.0 无运行时消费方，本服务只读写配置与留痕，不承接任何真实调用（配置即预留）。
 */
@Service
public class AiConfigService {

    /** 供应商封闭清单与显示名（ADR-003 §2.1；顺序即视图顺序，glm 默认居首）。 */
    private static final List<String> CLOSED_PROVIDERS = List.of("glm", "deepseek", "qwen", "kimi");
    private static final Map<String, String> PROVIDER_NAMES = Map.of(
            "glm", "智谱 GLM", "deepseek", "DeepSeek", "qwen", "通义千问 Qwen", "kimi", "Kimi");

    private static final Pattern BASE_URL_PATTERN = Pattern.compile("^https?://\\S+$");
    private static final int MAX_BASE_URL = 512;
    private static final int MAX_MODEL = 128;
    private static final String ENABLED_VALUE = "1";
    private static final String DISABLED_VALUE = "0";
    private static final String UNKNOWN_ACTOR = "unknown";
    private static final int MAX_ACTOR = 64;

    private static final String ALLOW_REASON = "允许（当前配置下网关放行）";
    private static final String DENIED_DISABLED_REASON = "拒绝：网关未启用";
    private static final String DENIED_BUDGET_REASON = "拒绝：超出预算";

    private final AiConfigStore store;

    public AiConfigService(final AiConfigStore store) {
        this.store = store;
    }

    /** 合并视图（E1/E2 出参；封闭清单顺序，configured = 地址或模型名至少一项非空）。 */
    public AiConfigViews.AiConfigView view() {
        GatewayState gateway = store.loadGatewayState();
        Map<String, ProviderState> providers = new LinkedHashMap<>();
        store.loadProviderStates().forEach(state -> providers.put(state.providerId(), state));
        List<AiConfigViews.ProviderView> rows = new ArrayList<>();
        for (String providerId : CLOSED_PROVIDERS) {
            ProviderState state = providers.get(providerId);
            String baseUrl = state == null ? null : state.baseUrl();
            String model = state == null ? null : state.model();
            rows.add(new AiConfigViews.ProviderView(providerId, PROVIDER_NAMES.get(providerId),
                    providerId.equals(gateway.defaultProvider()), baseUrl, model,
                    baseUrl != null || model != null));
        }
        return new AiConfigViews.AiConfigView(gateway.enabled(), gateway.defaultProvider(),
                gateway.tokenBudget(), List.copyOf(rows));
    }

    /** 全量保存（唯一写入口）：校验 → 逐字段 diff → 同事务落库与留痕（值未变化零留痕行）。 */
    @Transactional
    public AiConfigViews.AiConfigView save(final AiConfigViews.AiConfigSaveRequest request,
            final String changedBy) {
        validate(request);
        GatewayState current = store.loadGatewayState();
        Map<String, ProviderState> currentProviders = new LinkedHashMap<>();
        store.loadProviderStates().forEach(state -> currentProviders.put(state.providerId(), state));
        String actor = normalizeActor(changedBy);

        List<ChangeEntry> entries = new ArrayList<>();
        if (current.enabled() != request.enabled()) {
            entries.add(new ChangeEntry(actor, "enabled", boolValue(current.enabled()),
                    boolValue(request.enabled())));
        }
        if (!current.defaultProvider().equals(request.defaultProvider())) {
            entries.add(new ChangeEntry(actor, "defaultProvider", current.defaultProvider(),
                    request.defaultProvider()));
        }
        if (current.tokenBudget() != request.tokenBudget()) {
            entries.add(new ChangeEntry(actor, "tokenBudget", Long.toString(current.tokenBudget()),
                    Long.toString(request.tokenBudget())));
        }
        for (AiConfigViews.ProviderSaveItem item : request.providers()) {
            ProviderState old = currentProviders.get(item.providerId());
            String oldBaseUrl = old == null ? null : old.baseUrl();
            String oldModel = old == null ? null : old.model();
            String newBaseUrl = normalize(item.baseUrl());
            String newModel = normalize(item.model());
            if (!normalizedEquals(oldBaseUrl, newBaseUrl)) {
                entries.add(new ChangeEntry(actor, "providers." + item.providerId() + ".baseUrl",
                        oldBaseUrl, newBaseUrl));
            }
            if (!normalizedEquals(oldModel, newModel)) {
                entries.add(new ChangeEntry(actor, "providers." + item.providerId() + ".model",
                        oldModel, newModel));
            }
        }

        store.saveGatewayState(request.enabled(), request.defaultProvider(), request.tokenBudget(), actor);
        for (AiConfigViews.ProviderSaveItem item : request.providers()) {
            store.saveProviderState(item.providerId(), normalize(item.baseUrl()), normalize(item.model()));
        }
        if (!entries.isEmpty()) {
            store.appendChanges(entries);
        }
        return view();
    }

    /** 判定预览（E3）：当前配置 → GatewayControl.check（已用量恒 0，V1.0 无用量源）→ 三态映射。 */
    public AiConfigViews.AiConfigPreviewView preview() {
        GatewayState gateway = store.loadGatewayState();
        ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setEnabled(gateway.enabled());
        properties.setDefaultProvider(gateway.defaultProvider());
        properties.setTokenBudget(gateway.tokenBudget());
        ControlDecision decision = GatewayControl.check(properties, 0L);
        return switch (decision) {
            case ALLOW -> new AiConfigViews.AiConfigPreviewView("ALLOW", ALLOW_REASON);
            case DENIED_DISABLED -> new AiConfigViews.AiConfigPreviewView(
                    "DENIED_DISABLED", DENIED_DISABLED_REASON);
            case DENIED_BUDGET_EXCEEDED -> new AiConfigViews.AiConfigPreviewView(
                    "DENIED_BUDGET_EXCEEDED", DENIED_BUDGET_REASON);
        };
    }

    /** 变更留痕分页（E4；沿 RbacPermissionService.changes 口径）。 */
    public PageResult<AiConfigChangeView> changes(final Integer pageNum, final Integer pageSize) {
        PageQuery query = PageQuery.of(pageNum, pageSize, null);
        return PageResult.of(store.changes(query.offset(), query.pageSize()), store.countChanges(), query);
    }

    /** 校验（拒绝统一 1014C0001，消息常量细分可区分；库与留痕零变化由先校验后写保证）。 */
    private void validate(final AiConfigViews.AiConfigSaveRequest request) {
        if (request.enabled() == null) {
            throw new BizException(AiConfigErrorCodes.PARAM_INVALID, AiConfigErrorCodes.MSG_ENABLED_INVALID);
        }
        if (request.providers() == null || request.providers().size() != CLOSED_PROVIDERS.size()
                || request.providers().stream().map(AiConfigViews.ProviderSaveItem::providerId)
                        .distinct().count() != CLOSED_PROVIDERS.size()
                || request.providers().stream().map(AiConfigViews.ProviderSaveItem::providerId)
                        .anyMatch(id -> !CLOSED_PROVIDERS.contains(id))) {
            throw new BizException(AiConfigErrorCodes.PARAM_INVALID, AiConfigErrorCodes.MSG_PROVIDER_NOT_IN_LIST);
        }
        if (isBlank(request.defaultProvider()) || !CLOSED_PROVIDERS.contains(request.defaultProvider())) {
            throw new BizException(AiConfigErrorCodes.PARAM_INVALID,
                    AiConfigErrorCodes.MSG_DEFAULT_PROVIDER_NOT_IN_LIST);
        }
        if (request.tokenBudget() == null || request.tokenBudget() < 0) {
            throw new BizException(AiConfigErrorCodes.PARAM_INVALID, AiConfigErrorCodes.MSG_BUDGET_INVALID);
        }
        for (AiConfigViews.ProviderSaveItem item : request.providers()) {
            String baseUrl = normalize(item.baseUrl());
            if (baseUrl != null && (baseUrl.length() > MAX_BASE_URL
                    || !BASE_URL_PATTERN.matcher(baseUrl).matches())) {
                throw new BizException(AiConfigErrorCodes.PARAM_INVALID, AiConfigErrorCodes.MSG_BASE_URL_INVALID);
            }
            String model = normalize(item.model());
            if (model != null && model.length() > MAX_MODEL) {
                throw new BizException(AiConfigErrorCodes.PARAM_INVALID, AiConfigErrorCodes.MSG_MODEL_INVALID);
            }
        }
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    /** 空串归一为 null（未配置态），比较与落库共用同一口径。 */
    private static String normalize(final String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean normalizedEquals(final String left, final String right) {
        return normalize(left) == null ? normalize(right) == null : normalize(left).equals(normalize(right));
    }

    private static String boolValue(final boolean enabled) {
        return enabled ? ENABLED_VALUE : DISABLED_VALUE;
    }

    private static String normalizeActor(final String actor) {
        if (isBlank(actor)) {
            return UNKNOWN_ACTOR;
        }
        return actor.length() > MAX_ACTOR ? actor.substring(0, MAX_ACTOR) : actor;
    }
}
