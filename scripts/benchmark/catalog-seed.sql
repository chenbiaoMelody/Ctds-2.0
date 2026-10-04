-- ============================================================================
-- 万级目录数据集造数脚本（WBS-3.3.7 hifi §4.1；WBS 行 359「4.2.1 性能测试数据集与脚本」可复用份额）
-- 目标库：catalog 服务库（须已完成 Flyway V1~V4 迁移，category_node 24 条种子类目已存在）
-- 占位参数（整脚本执行前替换；不替换时 MySQL 解析报错，防误执行）：
--   @SPACE_BASE@ = 空间段基址（默认 990001；占用 @SPACE_BASE@ ~ @SPACE_BASE@+3 共 4 段）
--   @ROW_TOTAL@  = 资源行数（默认 10000；产品 1:1 同数）
--   @PER_SPACE@  = 每空间段行数（默认 2500 = ROW_TOTAL / 4）
-- 数据构成（hifi §4.1 定稿）：
--   资源 10000（dataset）+ 产品 10000（data_product）1:1，dataset_id 逻辑引用不悬空；
--   挂 4 个独立空间段（每段 @PER_SPACE@ 行）；24 条种子类目均匀分布（ELT 轮转，误差 ≤1 行）；
--   约 20% 产品简介含固定检索词「基准检索关键词」（n MOD 5 = 0，精确 2000 行）；
--   产品全部 LISTED，listed_at 按序号递增（目录默认排序键走 idx_listed_at）；
--   不触任何留痕表（检索链路不涉）；normalized_product_name 留 NULL（直造行口径，V4 列注释）。
-- 可识别性：data_no 用 DSBENCH 前缀 + 序号（与应用 DS+日期取号格式区分，一眼可辨基准行）；
--   资源/产品/提供方/属主命名统一带 bench- / 基准 字样，清理与审计可按段精确定位。
-- 执行通道：mysql 客户端（--default-character-set=utf8mb4）或应用侧 JDBC 逐语句执行；
--   不建议 PowerShell 5.1 Get-Content 直读（无 BOM UTF-8 中文会被静默读错）。
-- 边界：演示库 ctds_catalog 零执行（WBS-3.3.7 Q3-A 承诺）；配套清理脚本 = 同目录 catalog-cleanup.sql。
-- ============================================================================

-- 递归 CTE 行数上限放开（默认 1000，造数需 1 万）
SET SESSION cte_max_recursion_depth = 1000000;

-- 资源 1 万行（每空间段 @PER_SPACE@ 行；名称全局带序号，同空间唯一键 uk_space_norm_name 天然满足）
INSERT INTO dataset
    (data_no, space_id, owner_subject_no, name, normalized_name, type, intro,
     semantic_tags, declare_category, declare_level, declare_important, status)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @ROW_TOTAL@
)
SELECT
    CONCAT('DSBENCH', LPAD(n, 12, '0')),
    @SPACE_BASE@ + FLOOR((n - 1) / @PER_SPACE@),
    CONCAT('bench-owner-', @SPACE_BASE@ + FLOOR((n - 1) / @PER_SPACE@)),
    CONCAT('基准资源-', n),
    CONCAT('基准资源-', n),
    'DATASET',
    '目录检索基准资源行（WBS-3.3.7 造数）',
    '["基准标签"]',
    ELT(n MOD 24 + 1, '交通运输', '工业与能源', '农业农村', '金融', '医疗健康', '气象与环境',
        '地理空间', '文化与旅游', '智慧交通', '物流货运', '工业制造', '能源电力', '农业生产',
        '乡村振兴', '银行保险', '普惠金融', '医疗服务', '公共卫生', '气象服务', '生态环境',
        '测绘地理', '遥感影像', '文化服务', '旅游出行'),
    'L2',
    0,
    'ACTIVE'
FROM seq;

-- 产品 1 万行（与资源 1:1：按 空间段 + 归一化名 回连取 dataset_id；同提供方产品名全局带序号唯一）
INSERT INTO data_product
    (product_name, intro, product_type, pricing_model, status, provider_subject_no,
     dataset_id, category_code, listed_at)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @ROW_TOTAL@
)
SELECT
    CONCAT('基准产品-', n),
    CASE WHEN n MOD 5 = 0 THEN CONCAT('含基准检索关键词的产品简介-', n)
         ELSE CONCAT('普通产品简介-', n) END,
    'DATASET',
    'FREE',
    'LISTED',
    CONCAT('bench-provider-', @SPACE_BASE@ + FLOOR((n - 1) / @PER_SPACE@)),
    d.id,
    ELT(n MOD 24 + 1, 'transport', 'industry', 'agriculture', 'finance', 'health', 'environment',
        'geography', 'culture', 'transport-smart', 'transport-logistics', 'industry-manufacturing',
        'industry-energy', 'agriculture-production', 'agriculture-rural', 'finance-banking',
        'finance-inclusive', 'health-medical', 'health-public', 'environment-weather',
        'environment-ecology', 'geography-mapping', 'geography-remote-sensing', 'culture-service',
        'culture-tourism'),
    DATE_ADD('2026-01-01 00:00:00', INTERVAL n SECOND)
FROM seq
JOIN dataset d
    ON d.space_id = @SPACE_BASE@ + FLOOR((n - 1) / @PER_SPACE@)
   AND d.normalized_name = CONCAT('基准资源-', n);
