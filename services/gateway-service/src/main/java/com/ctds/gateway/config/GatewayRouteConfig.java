package com.ctds.gateway.config;

import java.util.HashSet;
import java.util.Set;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 静态路由装配（hifi §1.6）：路由来自 {@code ctds.gateway.routes}（id / uri / predicates / filters），
 * 不引入服务发现（登记演进项）。
 *
 * <p>本卡支持形态为 <b>谓词仅 {@code Path=<模板>}、过滤器列表为空</b>（V1.0 无路由级过滤器）；
 * 出现其它形态一律启动失败（fail-fast）——配置项被静默忽略等于安全口径失守，禁止。</p>
 *
 * <p>路径模板写入路由 metadata（{@link #PATH_TEMPLATE_METADATA}），供限流 key 与计量事件
 * "被交付资源"字段取用（用路径模板而非原始路径，避免路径参数造成限流 key 爆炸，hifi §3-1）。</p>
 */
@Configuration
public class GatewayRouteConfig {

    /** 路由元数据键：路径模板（配置值原样，形如 /api/v1/catalog/products/**）。 */
    public static final String PATH_TEMPLATE_METADATA = "path-template";

    private static final String PATH_PREDICATE_PREFIX = "Path=";

    @Bean
    public RouteLocator ctdsGatewayRoutes(final RouteLocatorBuilder builder,
            final GatewayProperties properties) {
        final RouteLocatorBuilder.Builder routes = builder.routes();
        final Set<String> ids = new HashSet<>();
        for (final GatewayProperties.Route definition : properties.getRoutes()) {
            validate(definition, ids);
            final String template = pathTemplate(definition);
            routes.route(definition.getId(), route -> route.path(template)
                    .metadata(PATH_TEMPLATE_METADATA, template)
                    .uri(definition.getUri()));
        }
        return routes.build();
    }

    private static void validate(final GatewayProperties.Route definition, final Set<String> ids) {
        if (definition.getId() == null || definition.getId().isBlank()) {
            throw new IllegalStateException("ctds.gateway.routes 存在缺少 id 的路由定义（hifi §1.6）");
        }
        if (!ids.add(definition.getId())) {
            throw new IllegalStateException("ctds.gateway.routes 路由 id 重复: " + definition.getId());
        }
        if (definition.getUri() == null || definition.getUri().isBlank()) {
            throw new IllegalStateException(
                    "ctds.gateway.routes[" + definition.getId() + "] 缺少 uri（hifi §1.6）");
        }
        if (!definition.getFilters().isEmpty()) {
            throw new IllegalStateException("ctds.gateway.routes[" + definition.getId()
                    + "].filters 本卡仅支持空列表（V1.0 无路由级过滤器，hifi §1.6）");
        }
    }

    private static String pathTemplate(final GatewayProperties.Route definition) {
        for (final String predicate : definition.getPredicates()) {
            if (predicate != null && predicate.startsWith(PATH_PREDICATE_PREFIX)) {
                final String template = predicate.substring(PATH_PREDICATE_PREFIX.length()).trim();
                if (!template.isEmpty()) {
                    return template;
                }
            }
        }
        throw new IllegalStateException("ctds.gateway.routes[" + definition.getId()
                + "] 仅支持 Path=<模板> 谓词（hifi §1.6）");
    }
}
