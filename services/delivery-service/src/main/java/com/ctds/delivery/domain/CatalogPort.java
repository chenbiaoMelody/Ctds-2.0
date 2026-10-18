package com.ctds.delivery.domain;

/** 目录域内部只读衔接（产品形态单一事实源）。 */
public interface CatalogPort {

    /** 产品形态（API/DATASET/REPORT/MODEL）；不存在/不可用 → 1009S0002。 */
    String productType(long productId);
}
