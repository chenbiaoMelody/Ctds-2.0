package com.ctds.connector.domain;

import com.ctds.connector.sdk.spi.SourceAdapter;
import com.ctds.connector.sdk.spi.SourceType;
import java.util.Optional;

/** 源适配器目录端口（domain 表达"按类型取适配器"；实现落 infrastructure 注册表）。 */
public interface SourceAdapterCatalog {

    /** 按源类型取适配器；未开放类型 = 空（平台侧映射 400 {@code 1010C0011}）。 */
    Optional<SourceAdapter> find(SourceType type);
}
