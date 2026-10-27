package com.ctds.connector.infrastructure;

import com.ctds.connector.sdk.spi.SourceAdapter;
import com.ctds.connector.sdk.spi.SourceType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 源适配器注册表（hifi §8）：按 {@link SourceType} 索引适配器；未注册类型 → {@code Optional.empty()}
 * （平台侧映射 400 {@code 1010C0011}，3.6.6 落地只需注册实现，端点与服务零改动）。
 *
 * <p>同类型多实现 = 启动失败（防第二实现与行为不一致）。</p>
 */
@Component
public class SourceAdapterRegistry {

    private final Map<SourceType, SourceAdapter> adapters;

    public SourceAdapterRegistry(final List<SourceAdapter> adapterBeans) {
        final Map<SourceType, SourceAdapter> indexed = new EnumMap<>(SourceType.class);
        for (final SourceAdapter adapter : adapterBeans) {
            if (indexed.putIfAbsent(adapter.type(), adapter) != null) {
                throw new IllegalStateException("同一源类型存在多个适配器实现：" + adapter.type());
            }
        }
        this.adapters = Map.copyOf(indexed);
    }

    /** 按源类型取适配器（未开放类型 = 空）。 */
    public Optional<SourceAdapter> find(final SourceType type) {
        return type == null ? Optional.empty() : Optional.ofNullable(adapters.get(type));
    }
}
