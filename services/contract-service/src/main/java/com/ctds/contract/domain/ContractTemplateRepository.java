package com.ctds.contract.domain;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.util.List;
import java.util.Optional;

/**
 * 合约模板仓储接口（六边形端口；实现 = infrastructure.JdbcContractTemplateRepository）。
 *
 * <p><b>版本行不可变</b>（hifi §6）：本接口<b>无任何更新版本行的方法</b>——修订 = {@link #revise}
 * 新增版本行 + 前移主表指针，旧版本行内容永不改写（编译期保证 + 测试锚 T8 反向探针）。</p>
 */
public interface ContractTemplateRepository {

    /** 新增模板（三写同事务：INSERT 主表 + INSERT 版本 V1 + INSERT 留痕；返回主表 id）。 */
    long create(ContractTemplate template, TemplateVersion version, TemplateActionLog log);

    /** 按模板编号取模板。 */
    Optional<ContractTemplate> findByNo(String templateNo);

    /** 取模板指定版本（版本快照读取，QV2）。 */
    Optional<TemplateVersion> findVersion(String templateNo, int versionNo);

    /** 版本历史（旧版本保留可查——行为 1 规则 3；运营读面 R2）。 */
    List<TemplateVersion> listVersions(String templateNo);

    /** 修订（三写同事务：INSERT 版本 Vn+1 + UPDATE 主表当前版本指针 + INSERT 留痕）。 */
    void revise(TemplateVersion newVersion, TemplateActionLog log);

    /** 启停（两写同事务：UPDATE 主表状态 + INSERT 留痕）。 */
    void updateStatus(ContractTemplate template, TemplateStatus target, TemplateActionLog log);

    /** 拒绝留痕（独立写入，主事务回滚不影响拒绝留痕——沿 catalog 先例）。 */
    void insertLog(TemplateActionLog log);

    /** 已启用模板分页（已入驻浏览读面 R4——仅 ENABLED，Q6-A）。 */
    PageResult<ContractTemplate> pageEnabled(TemplateType type, PageQuery page);

    /** 全量模板分页（运营维护视图 R1——含停用；status/type 可选过滤）。 */
    PageResult<ContractTemplate> pageManage(TemplateStatus status, TemplateType type, PageQuery page);

    /** 留痕分页（R3；templateNo 可选过滤）。 */
    PageResult<TemplateActionLog> pageLogs(String templateNo, PageQuery page);

    /** 同类型归一化名判重（预查；uk_type_norm_name 唯一索引兜底并发窗口）。 */
    boolean existsByTypeAndNormalizedName(TemplateType type, String normalizedName);

    /** 模板编号全局序号原子自增取号（序号表 LAST_INSERT_ID 技巧；返回下一个序号）。 */
    int nextTemplateNoSeq();
}
