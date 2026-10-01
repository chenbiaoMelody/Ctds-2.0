-- 统一目录服务库表（WBS-3.3.4，hifi §3 定稿；规格 docs/specs/C-3.1-2.3-数据目录与资源.md V1.0
-- 行为 5（检索与分类）/ 行为 6（订阅与收藏）/ 行为 7 规则 1/2/5 + §末未定义项「类目树与种子类目」）。
-- 六表承载：category_node（平台受控类目树，2 级 + 种子随迁移内置）、data_product（数据产品最小载体，
-- 封装/上下架/注销写面归 3.3.5）、product_favorite / product_subscription（收藏/订阅关系，条目保留不删、
-- 状态读时计算）、product_interaction_log（收藏订阅动作与拒绝留痕）、product_action_log（产品变更留痕，
-- 载体本卡建、写入动作码集合随 3.3.5 登记，本卡读面 R8 对本表只读）。
-- V1/V2 零改动：不新增列、不改语义、不回填历史（Q2-A/Q6-A，沿 3.3.3 先例）。
-- 跨表引用（data_product.dataset_id → dataset.id、category_code → category_node.category_code、
-- subject_no/provider_subject_no → ctds_subject.subject）一律逻辑引用不建外键（沿 V1 先例）。
-- 排序规则：各表未显式声明 COLLATE，落 MySQL 8 默认 utf8mb4_0900_ai_ci——大小写/重音不敏感
-- （成员校验/检索判重为"过阻断"方向非绕过）；0900 系 NO PAD，尾随空格参与比较，防重依赖应用层
-- 写入已归一化值。
-- 数据分级落级（WBS-3.3.4 hifi §6，分级规范 §6.1 同步行）：六表 = CAT-03 L1（目录元数据与公开词汇，
-- 检索 ≠ 可访问；留痕不含敏感原文，沿 dataset_action_log 四要素模式）。
-- 两套受控集合红线（3.3.3 hifi §9）：类目树（category/categories 术语面）与语义标签词表
-- （vocabulary/term 术语面）物理分离，本迁移不触碰 V2 两表。

-- 平台受控类目树：2 级（parent_code NULL = 一级）；平台唯一受控类目集合（Q2-A）。
-- V1.0 无维护写面（随迁移内置、只读面）；normalized_name = 应用侧 DatasetNameNormalizer 产出后
-- 写入（成员校验比对口径唯一来源，DB 不归一化，沿 tag_term.normalized_term 同款）。
CREATE TABLE category_node (
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    category_code   VARCHAR(32) NOT NULL COMMENT '类目码（语义码小写连字符，ADR-005 命名；平台受控、随迁移内置）',
    category_name   VARCHAR(64) NOT NULL COMMENT '类目名（展示用；资源分类申报落库存原文）',
    normalized_name VARCHAR(64) NOT NULL COMMENT '归一化类目名（应用侧 DatasetNameNormalizer 产出，成员校验比对口径）',
    parent_code     VARCHAR(32) NULL COMMENT '父类目码（NULL=一级类目；二级指向既有一级 category_code）',
    sort_order      INT         NOT NULL DEFAULT 0 COMMENT '同级展示排序（升序；同级内递增）',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（无维护写面故无 updated_at）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_code (category_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '平台受控类目树（2级；种子随迁移内置；类目成员校验比对面；与语义标签词表两套受控集合物理分离）';

-- 数据产品最小载体表：一行 = 一个数据产品（统一目录的检索与订购单元，规格行为 5）。
-- 字段全部来自规格已裁决明文（Q1-A 最小载体）；产品业务编号规则归 3.3.5 落定（本卡路径参数
-- 沿 R2 先例用自增 id，不建取号构件）；封装/定价数值/上下架流程/注销写面归 3.3.5（其迁移增列）。
-- status 枚举封闭（测试探针锚定）：DRAFT 未上架 / LISTED 已上架 / DELISTED 已下架 / CANCELLED 已注销
-- （状态机未上架→已上架⇄已下架 + 已注销，规格行为 4 规则 1）。
-- uk_provider_product_name = 产品名称同一提供方内唯一（规格行为 3 规则 5，Q3-A；存储引擎原子层兜底）。
CREATE TABLE data_product (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键（对外即 REST /data-products/{id} 的 {id}，沿 R2 先例）',
    product_name       VARCHAR(128) NOT NULL COMMENT '产品名称（原始输入，展示用；同一提供方内唯一）',
    intro              VARCHAR(512) NULL COMMENT '产品简介（关键词检索命中字段之一）',
    product_type       VARCHAR(16)  NOT NULL COMMENT '产品形态：API接口/DATASET数据集/REPORT报告/MODEL模型（四类受控枚举，沿资源四类同款）',
    pricing_model      VARCHAR(16)  NOT NULL COMMENT '定价模型：FREE免费/PER_CALL按次/MONTHLY包月/REVENUE_SHARE交易额分成（四档，规格行为3规则3；价格数值字段归3.3.5）',
    status             VARCHAR(16)  NOT NULL COMMENT '状态机（枚举封闭）：DRAFT未上架/LISTED已上架/DELISTED已下架/CANCELLED已注销（写面归3.3.5；目录仅呈现 LISTED）',
    provider_subject_no VARCHAR(32) NOT NULL COMMENT '提供方主体编号（逻辑引用 ctds_subject.subject.subject_no；产品名称唯一性口径 = 同一提供方）',
    dataset_id         BIGINT       NOT NULL COMMENT '来源资源 id（逻辑引用 dataset.id；一资源多产品，规格行为 3 规则 4）',
    category_code      VARCHAR(32)  NULL COMMENT '所属类目码（字符串载体，挂 category_node.category_code；资源申报类目→产品继承→目录过滤同源传导，Q2-A）',
    listed_at          DATETIME     NULL COMMENT '上架时间（目录默认排序键 listed_at DESC；非在架态为 NULL）',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_product_name (provider_subject_no, product_name),
    KEY idx_status_category (status, category_code),
    KEY idx_listed_at (listed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '数据产品最小载体表（一行=一个产品；目录检索对象；封装/上下架/注销写面归3.3.5，本表由其迁移增列扩展）';

-- 产品收藏关系表：一行 = 一主体收藏一产品（行为 6 规则 1）。
-- 幂等 = uk_subject_product 唯一键兜底 + 先查后插（命中即重放首次结果，零新增副作用，ADR-007 重放语义）；
-- 下架/注销后条目保留不删（行为 6 规则 3），产品当前状态由读面联 data_product 读时计算（Q5-A）。
CREATE TABLE product_favorite (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    subject_no VARCHAR(32) NOT NULL COMMENT '收藏主体编号（逻辑引用 ctds_subject.subject.subject_no；列表恒仅本人条目）',
    product_id BIGINT      NOT NULL COMMENT '产品 id（data_product.id，逻辑引用不建外键）',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间（幂等重放返回的首次时间）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_subject_product (subject_no, product_id),
    KEY idx_product_id (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '产品收藏关系表（一行=一主体收藏一产品；幂等=唯一键兜底+重放首次结果；下架/注销后条目保留不删，状态读时计算）';

-- 产品订阅关系表：一行 = 一主体订阅一产品（行为 6 规则 2）。
-- 变更感知 V1.0 = 订阅关系可查 + 产品变更留痕可查（R8 限本人订阅者），不承诺实时推送（Q6-A，
-- 通知渠道 3.7.2 评估）；条目保留不删、状态读时计算，沿 product_favorite 同款口径。
CREATE TABLE product_subscription (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    subject_no VARCHAR(32) NOT NULL COMMENT '订阅主体编号（逻辑引用 ctds_subject.subject.subject_no；列表恒仅本人条目）',
    product_id BIGINT      NOT NULL COMMENT '产品 id（data_product.id，逻辑引用不建外键）',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '订阅时间（幂等重放返回的首次时间）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_subject_product (subject_no, product_id),
    KEY idx_product_id (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '产品订阅关系表（一行=一主体订阅一产品；变更感知=产品变更留痕可查；条目保留不删，状态读时计算）';

-- 目录域收藏订阅动作留痕：四动作（收藏/取消/订阅/退订）+ DENIED 拒绝留痕（行为 6 规则 4、行为 7 规则 4
-- 越权探测可取证）；幂等重放不新增留痕（ADR-007 零新增副作用）；资格拒绝/未认证/无权限零留痕零副作用
-- （沿"资格探针零单据"先例）。deny_reason 落错误码尾号（如 C0011——服务端常量，不含用户输入，
-- 沿 dataset_action_log.reason_code 同款）。
CREATE TABLE product_interaction_log (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    subject_no  VARCHAR(32) NOT NULL COMMENT '操作者主体编号（DENIED 时 = 被拒者——四要素"谁"）',
    product_id  BIGINT      NOT NULL COMMENT '产品 id（data_product.id——四要素"对哪个产品"）',
    action      VARCHAR(16) NOT NULL COMMENT '动作码（枚举封闭）：FAVORITE收藏/UNFAVORITE取消收藏/SUBSCRIBE订阅/UNSUBSCRIBE退订',
    outcome     VARCHAR(16) NOT NULL COMMENT '结果（枚举封闭）：SUCCEEDED成功/DENIED拒绝（拒绝同样留痕）',
    deny_reason VARCHAR(32) NULL COMMENT '拒绝理由码（DENIED 时落错误码尾号，如 C0011；成功行为 NULL）',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间——四要素"何时"',
    PRIMARY KEY (id),
    KEY idx_subject_created (subject_no, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '目录域收藏订阅动作留痕（收藏/取消/订阅/退订+DENIED拒绝留痕；四要素：谁/何时/哪个产品/动作+结果；不含敏感原文；只插不改）';

-- 产品变更留痕表：产品侧统一操作留痕（封装/上架/下架/信息与定价变更等动作落定时登记动作码集合，
-- 归 3.3.5——沿 3.2.3 V2"留痕动作码登记"先例，action 注释不预设值域）；本卡建载体 + 读面 R8
-- （订阅者可查，按 created_at 倒序；非订阅者与产品不存在同形拒绝防枚举）；本卡测试以自造行验证读面。
CREATE TABLE product_action_log (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    product_id         BIGINT       NOT NULL COMMENT '产品 id（data_product.id，逻辑引用不建外键）',
    action             VARCHAR(32)  NOT NULL COMMENT '动作码（值域随 3.3.5 封装写面动作落定登记；不删不改既有码——沿 dataset_action_log 同款注释承诺）',
    operator_subject_no VARCHAR(32) NOT NULL COMMENT '操作者主体编号（四要素"谁"）',
    summary            VARCHAR(512) NULL COMMENT '变更摘要（业务可读文本，如字段 from→to；不含敏感原文与数据本体）',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间——四要素"何时"（R8 按本列倒序）',
    PRIMARY KEY (id),
    KEY idx_product_created (product_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '产品变更留痕表（载体随3.3.4建、写入动作码集合随3.3.5登记；订阅感知读面对本表只读；只插不改）';

-- 种子类目（Q3-A 定稿 24 条 = 8 一级 + 16 二级；code 用语义码小写连字符，沿 ADR-005 命名）。
-- 必含值盘点义务（hifi §3/§10）：走查/测试/演示库实际申报值实测 = "金融"（演示库 dataset 表 4 行
-- 现值、集成测试字面量、C-3.1 剧本正常登记步骤一致）——已被一级类目 finance 覆盖，无需增补种子行。
-- normalized_name = 应用侧 DatasetNameNormalizer 对 category_name 的归一化结果（种子名均为无空白
-- 纯中文/常规文本，归一化 = 恒等）。类目内容为业务事项：改种子 = 改本段种子行（DTO/接口零改动）。
INSERT INTO category_node (category_code, category_name, normalized_name, parent_code, sort_order) VALUES
    ('transport', '交通运输', '交通运输', NULL, 1),
    ('industry', '工业与能源', '工业与能源', NULL, 2),
    ('agriculture', '农业农村', '农业农村', NULL, 3),
    ('finance', '金融', '金融', NULL, 4),
    ('health', '医疗健康', '医疗健康', NULL, 5),
    ('environment', '气象与环境', '气象与环境', NULL, 6),
    ('geography', '地理空间', '地理空间', NULL, 7),
    ('culture', '文化与旅游', '文化与旅游', NULL, 8),
    ('transport-smart', '智慧交通', '智慧交通', 'transport', 1),
    ('transport-logistics', '物流货运', '物流货运', 'transport', 2),
    ('industry-manufacturing', '工业制造', '工业制造', 'industry', 1),
    ('industry-energy', '能源电力', '能源电力', 'industry', 2),
    ('agriculture-production', '农业生产', '农业生产', 'agriculture', 1),
    ('agriculture-rural', '乡村振兴', '乡村振兴', 'agriculture', 2),
    ('finance-banking', '银行保险', '银行保险', 'finance', 1),
    ('finance-inclusive', '普惠金融', '普惠金融', 'finance', 2),
    ('health-medical', '医疗服务', '医疗服务', 'health', 1),
    ('health-public', '公共卫生', '公共卫生', 'health', 2),
    ('environment-weather', '气象服务', '气象服务', 'environment', 1),
    ('environment-ecology', '生态环境', '生态环境', 'environment', 2),
    ('geography-mapping', '测绘地理', '测绘地理', 'geography', 1),
    ('geography-remote-sensing', '遥感影像', '遥感影像', 'geography', 2),
    ('culture-service', '文化服务', '文化服务', 'culture', 1),
    ('culture-tourism', '旅游出行', '旅游出行', 'culture', 2);
