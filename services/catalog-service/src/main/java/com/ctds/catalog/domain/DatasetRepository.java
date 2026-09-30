package com.ctds.catalog.domain;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 资源域仓储接口（WBS-3.3.2 hifi §9 四层契约；实现 = infrastructure.JdbcDatasetRepository）。
 * 一切状态变更同事务落留痕（space appendTransition 先例）；状态门槛 = UPDATE 带 status='ACTIVE'
 * 前置条件的乐观并发控制（0 行即拒，不先查后改——TOCTOU 防护）。
 *
 * <p><b>留痕 from/to 单一表达（沿 space 移交④口径）</b>：带留痕的仓储方法，留痕行的
 * from_value/to_value 一律由实现以方法参数统一回填——调用方构造值仅作摘要输入。</p>
 */
public interface DatasetRepository {

    // ==== 查询 ====

    /** 按技术主键取资源（W2/W3/R2 目标定位）。 */
    Optional<Dataset> findById(long datasetId);

    /** 同空间是否已有同名（归一化）资源——活跃与注销行共同参与（uk_space_norm_name 口径）。 */
    boolean existsBySpaceAndNormalizedName(long spaceId, String normalizedName);

    /** 名称是否已被本空间注销资源锁定（dataset_name_lock，行为 2 规则 3）。 */
    boolean existsInNameLock(long spaceId, String normalizedName);

    /**
     * 本人资源分页（R1；仅 owner_subject_no 命中，createdAt 倒序 + id 倒序稳定排序；
     * spaceId 非空时附加过滤——本人跨空间视图）。
     */
    PageResult<Dataset> searchByOwner(String ownerSubjectNo, Long spaceId, PageQuery page);

    // ==== 命令（实现须 @Transactional）====

    /**
     * 登记两写（hifi §4.2 步骤 7）：dataset 行 + REGISTER 留痕同事务落库（行为 1 规则 7）；
     * uk_space_norm_name 唯一约束兜底并发窗口（DuplicateKeyException → 1007C0001）。
     *
     * @return 新资源技术主键
     */
    long create(Dataset dataset, DatasetActionLog log);

    /**
     * 变更（hifi §4.3）：动态 SET + 逐字段留痕（每个实际变更字段一行 UPDATE 留痕，from→to 摘要）；
     * 乐观门槛 WHERE id AND status='ACTIVE'，0 行 → 1007C0007（终态/并发收紧）。
     */
    void updateFields(long datasetId, DatasetUpdate update, List<DatasetActionLog> logs);

    /**
     * 注销两写事务（hifi §4.1）：① status→DELETED（乐观门槛 WHERE id AND status='ACTIVE'，
     * 0 行 → 1007C0007）；② dataset_name_lock 写入（行为 2 规则 3 同空间锁定）；
     * ③ CANCEL 留痕。任一失败整体回滚；name_lock PK 冲突（理论不可达：uk_space_norm_name
     * 已挡）→ 事务回滚转 500 通用码（沿 3.2.3 防御性分支先例）。
     */
    void cancel(long datasetId, long spaceId, String normalizedName, DatasetActionLog log);

    /** 独立写一条留痕（DENIED 拒绝留痕等；只插不改）。 */
    void insertLog(DatasetActionLog log);

    /**
     * 数据标识当日序号原子取号（Q3-A，沿 subject nextDailySeq 先例）：与登记调用方同事务；
     * 当日从 1 起、跨日重置；上限 999999（超出按超限错误处理而非溢出编号）。
     */
    int nextDailySeq(LocalDate date);

    /** 变更载荷（null = 该项不变更；可变字段白名单 = 简介/标签/分类/级别，hifi §1.1 W2）。 */
    record DatasetUpdate(String intro, String semanticTagsJson, String declareCategory,
            DeclareLevel declareLevel) {
    }
}
