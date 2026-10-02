-- 产品封装与上下架服务扩展（WBS-3.3.5，hifi §3 定稿；规格 docs/specs/C-3.1-2.3-数据目录与资源.md V1.0
-- 行为 3（封装与定价）/ 行为 4（上下架管理）/ 行为 2 规则 2（注销引用保护实体化）+ §末未定义项五件落定）。
-- 本迁移 = 3.3.4 Q1-A 授权的"其迁移增列"：data_product 增两列 + 一唯一键；留痕表零结构变更，
-- 仅动作码值域注释登记（沿 DB-29 V4"仅列注释值域登记"先例）。V1/V2/V3 既有列与约束零改动。
-- 数据分级（WBS-3.3.5 hifi §6 Q9-A 复核）：data_product（含新增列）与 product_action_log 维持
-- CAT-03 L1（目录公开元数据，检索 ≠ 可访问；定价数值随目录公开展示为目录业务本意，
-- CAT-04 预设 L3 行 = 合约文本/计费账单等交易载体，归 C-4.x/3.7.6）。

-- data_product 增列（定价数值字段 Q2-A + 归一化产品名唯一判定口径，沿 dataset.normalized_name 同款）。
-- normalized_product_name 允许 NULL：封装写面（3.3.5+）写入恒非空；NULL = 3.3.4 期直造行
-- （测试/预置数据，不走封装写面），不参与唯一判定（MySQL 唯一键多 NULL 共存）——
-- 并发防重兜底不受影响：写面行归一化值非空，两请求同归一化名后插入方撞 uk_provider_norm_name。
ALTER TABLE data_product
    ADD COLUMN price_amount DECIMAL(12,2) NULL
        COMMENT '定价数值（Q2-A 单列语义随档位：按次=元/次、包月=元/月、分成=百分比0~100、免费档恒NULL；付费档未上架草稿态允许缺失，上架强制齐备 1007C0020）' AFTER pricing_model,
    ADD COLUMN normalized_product_name VARCHAR(128) NULL
        COMMENT '归一化产品名（应用侧 DatasetNameNormalizer 产出；同提供方唯一性判定口径沿 dataset.normalized_name 同款；封装写面写入恒非空，NULL = 直造行不参与唯一判定）' AFTER product_name,
    ADD UNIQUE KEY uk_provider_norm_name (provider_subject_no, normalized_product_name);

-- 留痕动作码值域登记（零结构变更；沿 3.2.3 V2"留痕动作码登记"与 DB-29 V4 先例——不删不改既有码）。
-- 产品侧：R8 订阅者可见值域 = CREATE/UPDATE/PUBLISH/DELIST/FORCE_DELIST/CANCEL 六类（变更类）；
-- DENIED_* 拒绝留痕与 GOVERNANCE_VIEW 治理查看留痕不对订阅者暴露（Q5-A）。
ALTER TABLE product_action_log
    MODIFY COLUMN action VARCHAR(32) NOT NULL COMMENT '动作码（值域随 WBS-3.3.5 封装写面登记，沿 3.2.3 V2 先例）：CREATE 封装 / UPDATE 信息与定价变更（summary 含 field:from→to） / PUBLISH 上架 / DELIST 下架 / FORCE_DELIST 强制下架（summary 含理由全文） / CANCEL 注销 / DENIED_CREATE / DENIED_UPDATE / DENIED_PUBLISH / DENIED_DELIST / DENIED_FORCE_DELIST / DENIED_CANCEL 拒绝留痕（reason 尾号同 dataset 口径） / GOVERNANCE_VIEW 运营方治理查看留痕——订阅者读面 R8 可见值域 = 变更类六码（Q5-A）；不删不改既有码';
-- 资源侧：追加治理查看留痕码（Q5-A 资源侧治理例外，DB-29 同款机制复用——通用码 + 实际登录主体）。
ALTER TABLE dataset_action_log
    MODIFY COLUMN action VARCHAR(32) NOT NULL COMMENT '动作码（值域 = WBS-3.3.2 hifi §3.3 登记 + WBS-3.3.5 追加：GOVERNANCE_VIEW 运营方治理查看留痕〔仅 admin 触发，Q5-A/DB-29 同款〕）；不删不改既有码';
