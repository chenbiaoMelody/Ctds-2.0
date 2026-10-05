-- ============================================================================
-- 万级目录数据集清理脚本（WBS-3.3.7 hifi §4.1；与同目录 catalog-seed.sql 配对使用）
-- 占位参数：与造数脚本一致（@SPACE_BASE@ / @ROW_TOTAL@ / @PER_SPACE@——本脚本实际只用 SPACE_BASE）。
-- 安全边界：只删 @SPACE_BASE@ ~ @SPACE_BASE@+3 四个基准空间段内的行（段外零触碰）；
--   先报数、后删除、删后核验 0 行；产品先于资源删除（dataset_id 逻辑引用方向）。
-- 提醒：演示库 ctds_catalog 本卡零执行（WBS-3.3.7 Q3-A）；供 WBS-4.2.1 与未来真链压测使用前，
--   先核对基准段（默认 990001~990004）未被业务占用——该段为目录基准专用保留段。
-- 执行通道：mysql 客户端（--default-character-set=utf8mb4）或应用侧 JDBC 逐语句执行；
--   不建议 PowerShell 5.1 Get-Content 直读（无 BOM UTF-8 中文会被静默读错）。
-- ============================================================================

-- ① 删前报数（两行 = 将被删除的规模，执行人留存核对）
SELECT COUNT(*) AS benchmark_datasets_before FROM dataset
WHERE space_id BETWEEN @SPACE_BASE@ AND @SPACE_BASE@ + 3;
SELECT COUNT(*) AS benchmark_products_before FROM data_product p
JOIN dataset d ON p.dataset_id = d.id
WHERE d.space_id BETWEEN @SPACE_BASE@ AND @SPACE_BASE@ + 3;

-- ② 删除（产品先、资源后）
DELETE p FROM data_product p
JOIN dataset d ON p.dataset_id = d.id
WHERE d.space_id BETWEEN @SPACE_BASE@ AND @SPACE_BASE@ + 3;
DELETE FROM dataset WHERE space_id BETWEEN @SPACE_BASE@ AND @SPACE_BASE@ + 3;

-- ③ 删后核验（两行均必须为 0；非 0 = 清理未完成，禁止收工）
SELECT COUNT(*) AS remaining_datasets_must_be_zero FROM dataset
WHERE space_id BETWEEN @SPACE_BASE@ AND @SPACE_BASE@ + 3;
SELECT COUNT(*) AS remaining_products_must_be_zero FROM data_product p
JOIN dataset d ON p.dataset_id = d.id
WHERE d.space_id BETWEEN @SPACE_BASE@ AND @SPACE_BASE@ + 3;
