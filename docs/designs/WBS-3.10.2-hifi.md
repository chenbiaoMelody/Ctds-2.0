# WBS-3.10.2 向量库与目录语义检索 MVP · 高保真设计（一次确认稿 = 编码契约候选）

| 项 | 内容 |
| --- | --- |
| 版本 | **V1.0（2026-11-17 编排师定稿一次确认，指示原文"定稿确认，三份转V1.0"）= 编码契约**；实现与本稿不一致 = 打回项；V0.9 = 设计段草案 |
| 任务卡 / 规格 / lofi | `docs/tasks/WBS-3.10.2-向量库与语义检索-2026-11-17.md`；`docs/specs/C-11.2-向量库与目录语义检索MVP.md` **V1.0（已批准）**（B1~B5）；`docs/designs/WBS-3.10.2-lofi.md` **V1.0（方向定稿）** |
| 确认状态 | Q1~Q6 已确认采 A（2026-11-17 编排师"确认 Q1~Q6 均采 A"）；**定稿一次确认已完成**（2026-11-17"定稿确认，三份转V1.0"；§13 签署；ADR-003 §7 勘误随定稿批准） |
| 技术基线 | JDK 17 + Maven（ADR-001 冻结，**MySQL 8**）；父 POM `spring-boot-starter-parent:3.5.16`；构件版本 **2.0.0-SNAPSHOT**；parent `<relativePath>../../pom.xml</relativePath>`（AGENTS §3）；catalog-service 既有 Flyway V1~V4，本卡顺延 V5 |

## §1 模块与文件清单（新增/修改面，精确）

### §1.1 新增 common 模块 `common/vector-store/`

artifactId **`common-vector-store`**，包根 **`com.ctds.common.vectorstore`**（沿 `com.ctds.common.modelgateway` 先例）。

| 文件（相对模块根） | 类型 | 职责 |
| --- | --- | --- |
| `pom.xml` | 构建 | parent ctds-platform；依赖仅 `org.springframework.boot:spring-boot-starter-test`（test，受管）；**零生产依赖** |
| `src/main/java/com/ctds/common/vectorstore/EmbeddingProvider.java` | interface | 向量化抽象：`dimensions()` + `embed(String)` |
| `.../HashingEmbeddingProvider.java` | final class | 本地确定性向量化（384 维；中文单字/二元组 + ASCII 词元；CRC32 哈希技巧；L2 归一化） |
| `.../EmbeddingTexts.java` | final utility | 文本规范化（NFKC/空白折叠/ASCII 小写）+ 源文本 SHA-256 指纹（非安全用途的内容变更检测） |
| `.../VectorStore.java` | interface | 向量存储与检索抽象：按 key 批量读取/写入 + 默认检索（首版实现 = MySQL 缓存表） |
| `.../VectorRecord.java` | record | 存储记录：key + 源文本哈希 + float[] 向量 |
| `.../VectorHit.java` | record | 排序结果：key + 相似度分数 |
| `.../VectorSearchResult.java` | record | 检索结果：本次加载的向量记录（供缓存变更检测）+ 排序命中 |
| `.../VectorMath.java` | final utility | 归一化 / 余弦相似度 / 零向量判定 / Top-K 排序（分数降序、key 升序稳定） |
| `src/test/java/com/ctds/common/vectorstore/EmbeddingTextsTest.java` | test | T1 规范化与源指纹 |
| `.../HashingEmbeddingProviderTest.java` | test | T1 确定性/维度/零向量/归一化 |
| `.../VectorMathTest.java` | test | T2 余弦/边界/Top-K |

### §1.2 catalog-service 新增文件

| 文件 | 类型 | 职责 |
| --- | --- | --- |
| `src/main/java/com/ctds/catalog/domain/SemanticCandidateRow.java` | record | 语义候选行：`CatalogProductRow` + 来源资源语义标签原文 |
| `src/main/java/com/ctds/catalog/application/SemanticSearchService.java` | service | B3 语义检索应用服务（资格 → 校验 → 候选 → 惰性向量化 → 排序 → 分页） |
| `src/main/java/com/ctds/catalog/infrastructure/JdbcProductVectorStore.java` | repository | `VectorStore` 的 MySQL 实现（`product_embedding` 表；BLOB 小端 float32） |
| `src/main/java/com/ctds/catalog/infrastructure/VectorStoreConfig.java` | config | `@Bean EmbeddingProvider`（`HashingEmbeddingProvider` 单例） |
| `src/main/resources/db/migration/V5__create_product_embedding.sql` | migration | B2 向量缓存表（仅新增；V1~V4 零改动） |
| `src/test/java/com/ctds/catalog/CatalogSemanticSearchIntegrationTest.java` | test | T3~T7（MockMvc + Testcontainers MySQL 8） |
| `src/test/java/com/ctds/catalog/CatalogProductVectorMigrationIntegrationTest.java` | test | T8（V5 表结构与 V1~V4 零扰动） |
| `src/test/java/com/ctds/catalog/CatalogSemanticSearchBenchmarkIntegrationTest.java` | test | T9（1k/10k P50/P95/max + JSON 留痕） |

### §1.3 修改文件（精确）

| 文件 | 改动 |
| --- | --- |
| 根 `pom.xml` | `<modules>` +1 行 `<module>common/vector-store</module>`（common 段尾）；`<dependencyManagement>` +1 节 `common-vector-store`（`${project.version}`） |
| `services/catalog-service/pom.xml` | +1 依赖 `com.ctds:common-vector-store`（版本走父 BOM/依赖管理） |
| `services/catalog-service/src/main/java/com/ctds/catalog/domain/DataProductRepository.java` | +1 方法 `listListedSemanticCandidates(List<String> categoryCodes)`（仅新增，不改既有方法） |
| `services/catalog-service/src/main/java/com/ctds/catalog/infrastructure/JdbcDataProductRepository.java` | +1 方法实现（候选 SQL：`status='LISTED'` + 分类子树 + `LEFT JOIN dataset` 取语义标签；复用既有 `mapRow`） |
| `services/catalog-service/src/main/java/com/ctds/catalog/interfaces/ProductCatalogController.java` | +1 GET 端点 + 构造器注入 `SemanticSearchService`（既有端点零改动） |

**零改动面**：R6/R7/R8/R9/R10 行为与响应；既有 21 个 catalog 测试文件；前端；网关；其他 services/common 模块；门禁配置（`scripts/gates/*`）；既有迁移 V1~V4；`application.yml` / `application-mysql.yml`；CI workflow。

## §2 接口契约（签名级）

```java
// common/vector-store —— 向量化抽象（B1）
public interface EmbeddingProvider {
    int dimensions();
    float[] embed(String text); // null/空白 → 零向量；确定性、无外部调用
}

// common/vector-store —— 本地确定性实现（B1；Q3-A）
public final class HashingEmbeddingProvider implements EmbeddingProvider {
    public static final int DEFAULT_DIMENSIONS = 384;
    public HashingEmbeddingProvider();                 // 384 维
    public HashingEmbeddingProvider(int dimensions);   // >0；测试可注入小维度
    @Override public int dimensions();
    @Override public float[] embed(String text);
}

// common/vector-store —— 文本规范化与源指纹（B1/B2）
public final class EmbeddingTexts {
    public static String canonicalize(String text);  // null → ""；NFKC；空白折叠；ASCII 小写；trim
    public static String sourceHash(String canonicalText); // SHA-256 小写十六进制 64 字符（非安全用途）
}

// common/vector-store —— 向量存储与检索抽象（B1；Q2-A 首版 MySQL 实现）
public interface VectorStore {
    Map<String, VectorRecord> findAll(Collection<String> keys); // 缺失 key 不返回，不抛异常
    void upsertAll(Collection<VectorRecord> records);           // 幂等 upsert（按 key）

    // 默认检索 = 加载候选向量 + 纯函数排序；未来 pgvector/Milvus 适配器可覆写为原生索引检索
    default VectorSearchResult search(float[] queryVector, Collection<String> keys) {
        Map<String, VectorRecord> vectors = findAll(keys);
        return new VectorSearchResult(vectors, VectorMath.rank(queryVector, vectors));
    }
}

public record VectorRecord(String key, String sourceHash, float[] vector) { }
public record VectorHit(String key, double score) { }
public record VectorSearchResult(Map<String, VectorRecord> vectors, List<VectorHit> hits) { }

// common/vector-store —— 纯函数工具（B1/B3）
public final class VectorMath {
    public static float[] normalize(float[] vector);
    public static double cosine(float[] a, float[] b);   // 任一零向量 → 0.0；维度不一致 → IllegalArgumentException
    public static boolean isZero(float[] vector);
    public static List<VectorHit> rank(float[] queryVector, Map<String, VectorRecord> records);
    // rank：分数降序；同分按 key 升序（稳定锚）；仅使用与 query 同维度的记录
}
```

```java
// catalog/domain —— 语义候选行（B2）
public record SemanticCandidateRow(CatalogProductRow product, String semanticTags) { }

// catalog/domain —— 既有端口仅新增方法（B2；不改既有方法）
List<SemanticCandidateRow> listListedSemanticCandidates(List<String> categoryCodes);

// catalog/application —— 语义检索应用服务（B3）
public class SemanticSearchService {
    public PageResult<CatalogProductRow> search(String q, String categoryCode, PageQuery page);
}

// catalog/interfaces —— 端点（B3）
@GetMapping(path = "/catalog/semantic-search", produces = MediaType.APPLICATION_JSON_VALUE)
@RequirePermission("catalog.read")
public ApiResult<PageResult<CatalogProductView>> semanticSearch(
        @RequestParam(required = false) String q,          // 业务必填；声明 optional 以返回 1007C0013 而非框架缺参
        @RequestParam(required = false) String categoryCode,
        @RequestParam(required = false) Integer pageNum,
        @RequestParam(required = false) Integer pageSize);
```

**实现注记**：`ProductCatalogController` 增加 `SemanticSearchService` 构造器参数（Spring 自动装配；既有测试零改动）；`DataProductRepository` 仅新增方法，唯一实现 `JdbcDataProductRepository` 同步实现。

## §3 向量化算法（B1；确定性、离线、零外呼）

1. **规范化** `EmbeddingTexts.canonicalize`：`null → ""`；Unicode **NFKC**；所有 `Character.isWhitespace` 码点折叠为单个半角空格；ASCII `A-Z` 转小写；首尾 trim。
2. **分词**（`HashingEmbeddingProvider.embed`）：
   - 扫描规范化文本的码点；**连续 Han 字符段**：每个单字一个词元 + 每对相邻字一个二元组词元（长度 1 的段仅单字）；
   - **连续 ASCII 字母/数字段**：整段小写作为一个词元；
   - 其他字符（标点、JSON 引号/括号等）跳过——语义标签原文可直接参与向量化，无需 JSON 解析。
3. **哈希技巧**：每个词元出现一次，`CRC32(UTF-8 bytes)` → `bucket = (int)(crc % dims)`，`sign = ((crc >>> 31) & 1L) == 0 ? +1 : -1`，`vector[bucket] += sign`（TF 权重 1.0）。
4. **归一化**：L2 范数归一；范数 < 1e-9 → 零向量。
5. **确定性**：CRC32、NFKC、码点扫描均与 JVM 实例/时间无关；同文本跨实例逐元素一致；维度默认 **384**（哈希桶数量，兼顾碰撞率与 BLOB 存储/计算成本；BLOB 1536 字节）。
6. **源指纹**：`sourceHash = SHA-256(canonicalSourceText UTF-8)` 小写十六进制 64 字符；仅用于缓存变更检测（非安全用途，不涉及密钥/加解密）。
7. **索引源文本**：`productName + "\n" + (intro == null ? "" : intro) + "\n" + (semanticTags == null ? "" : semanticTags)`；语义标签为 `dataset.semantic_tags` 原文 JSON 数组字符串，标点由分词器忽略。
8. **已知边界**：哈希技巧存在桶碰撞；相似度是词元重叠的近似，不是真实语义理解——该边界计入 B1/B5 诚实声明，不得对外宣称达到真实语义模型效果。

**边界**：纯标点/空白文本 → 零向量；查询向量为零 → 空结果页（200，total=0），不得退化为全量返回。

## §4 数据库（B2；仅新增 V5）

```sql
CREATE TABLE product_embedding (
    product_id   BIGINT            NOT NULL COMMENT '产品 id（逻辑引用 data_product.id，不建外键）',
    source_hash  CHAR(64)          NOT NULL COMMENT '索引源文本 SHA-256（小写十六进制；变更检测用，非安全用途）',
    dims         SMALLINT UNSIGNED NOT NULL COMMENT '向量维度（V1.0 恒 384）',
    vector       BLOB              NOT NULL COMMENT '向量二进制（小端 float32 序列；长度 = dims × 4）',
    updated_at   DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                                   COMMENT '最近回写时间',
    PRIMARY KEY (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '产品语义向量缓存表（WBS-3.10.2；查询时惰性重算+回写；下架/注销行保留，检索按 data_product.status LISTED 硬过滤）';
```

- **无外键**（沿项目逻辑引用先例）；**无业务列**；**无迁移数据**；V1~V4 零改动。
- 向量序列化：`ByteBuffer.allocate(dims * 4).order(ByteOrder.LITTLE_ENDIAN).putFloat(...)`；读取时字节长度非 4 的倍数或 `dims` 与字节长度不一致 → 视为缓存缺失（重算并回写，不抛异常）。
- `findAll` 按每批 **500** 个 key 查询（避免超长 IN / 参数上限）；`upsertAll` 逐行 UPSERT、方法级 `@Transactional`，幂等（同 key 覆盖）。
- 缓存行不主动删除（下架/注销后保留）；检索端 `status='LISTED'` 硬过滤保证零泄漏。

## §5 端点契约（B3）

| 项 | 内容 |
| --- | --- |
| 方法/路径 | `GET /api/v1/catalog/semantic-search`（catalog-service 内路径 `/catalog/semantic-search`） |
| 认证/权限 | 认证（`X-Ctds-Subject` 演示期身份头）+ `@RequirePermission("catalog.read")` + `CatalogAccessGuard.requireAdmitted` |
| 参数 `q` | 业务必填；trim 后 1~64 字符；空/纯空白/超长 → `1007C0013`（400） |
| 参数 `categoryCode` | 可选；非空时须为类目树内节点，过滤含子树；不存在 → `1007C0013`（400）；空白串视同未传 |
| 参数 `pageNum` / `pageSize` | 可选；复用 `PageQuery.of`（默认 1/10；pageSize 1~100；越界 → `1000C0001` 400） |
| 结果范围 | 仅 `data_product.status='LISTED'`；分类子树过滤；**不设相似度阈值**（total = 候选数，按相似度排序分页）；响应**不含 score、不含 status** |
| 响应 | `ApiResult<PageResult<CatalogProductView>>`；字段集与 R6 完全一致：`productId, productName, intro, productType, pricingModel, categoryCode, categoryName, providerSubjectNo, listedAt` + `list/total/pageNum/pageSize/totalPages` |
| 排序 | 相似度降序 → `listedAt` 降序（null 最后）→ `productId` 降序 |
| 纯标点 `q` | 200 + 空页（total=0） |
| 资格异常 | 未认证 401；缺 `catalog.read` 403；未入驻 `1007C0006`（403）；主体服务不可用 `1007S0001`（503，既有守卫语义） |

**示例**：`GET /api/v1/catalog/semantic-search?q=金融风控&categoryCode=finance&pageNum=1&pageSize=10` → 200，`data.list` 为按相似度排序的已上架产品视图，`data.total` 为候选总数。

## §6 检索流程（B2/B3；实现顺序）

1. `guard.requireSubject()` → `guard.requireAdmitted(subject)`（与 R6 同口径）；
2. 校验 `q`（trim 后 1~64）与 `categoryCode`（存在性 + 子树展开；空白串不展开）；
3. `dataProductRepository.listListedSemanticCandidates(subtreeCodes)`：`status='LISTED'` + `LEFT JOIN dataset` 取语义标签；空候选 → `PageResult.empty(page)`；
4. `queryVector = embeddingProvider.embed(q)`；`VectorMath.isZero(queryVector)` → `PageResult.empty(page)`；
5. `keys = 候选产品 id 字符串集`；`result = vectorStore.search(queryVector, keys)`；逐候选对照 `result.vectors()` 的 `sourceHash` / 向量长度：缺失 / hash 不一致 / 长度 ≠ 384 → `embeddingProvider.embed(canonicalSourceText)` 并加入待写列表；
6. 待写列表非空 → `vectorStore.upsertAll(待写列表)`（`@Transactional`、幂等 UPSERT；零业务留痕）→ **再次** `vectorStore.search(queryVector, keys)`（稳态只查一次；冷启动/变更行才查两次）；
7. `result.hits()` → key 映射回候选行 → 按 `score desc, listedAt desc nulls last, productId desc` 排序 → `PageResult.of(pageRows, hits.size(), page)`；
8. 不写 `product_interaction_log` / 审计 / 日志中的查询原文。

**并发与幂等**：并发首查可能重复计算同一产品向量；UPSERT 幂等（同源文本同结果），最后写入者胜；无锁、无事务跨读；`upsertAll` 独立事务，失败向上抛出（不吞异常）。

## §7 测试用例表（T1~T9 + E1~E4；先红后绿）

| # | 用例（类.方法族） | 断言（字面/结构） | 规格锚 |
| --- | --- | --- | --- |
| **T1** | EmbeddingTextsTest + HashingEmbeddingProviderTest | ① NFKC/空白折叠/ASCII 小写；② `sourceHash` 64 位小写十六进制、同文本稳定、变文本变化；③ 同文本跨实例逐元素相同、维度 384；④ L2 范数 ≈1；⑤ 纯标点 → 零向量 | B1-AC1/AC2/AC4 |
| **T2** | VectorMathTest | ① 相同向量余弦=1、正交=0、零向量=0；② 维度不一致抛 `IllegalArgumentException`；③ Top-K 分数降序、同分 key 升序；④ `isZero` 边界 | B1/B3 |
| **T3** | CatalogSemanticSearchIntegrationTest：范围与字段 | LISTED 出现；DRAFT/DELISTED/CANCELLED 不出现；响应字段白名单与 R6 完全一致（无 score/status）；仅目录元数据 | B3-AC2/B2-AC2 |
| **T4** | 同上：相关性排序 | "金融风控数据集（风控模型）" 排在 "农业气象数据（气象观测）" 之前；排序稳定可复现 | B3-AC1 |
| **T5** | 同上：标签与刷新 | 语义标签含"风控"的产品命中；简介改为"金融风控"后再检索排名上升（源哈希触发重算）；下架后立即不出现且缓存行仍在（状态硬过滤） | B2-AC3/AC4/AC5 |
| **T6** | 同上：分类与分页 | `categoryCode=finance` 仅父类目及子树；`pageSize=2` 两页总数/总页数/条目稳定；越界页空列表 total 不变 | B3-AC3/AC4 |
| **T7** | 同上：校验与资格 | `q` 缺失/空白/65 字符 → 400 `1007C0013`；类目不存在 → 400 `1007C0013`；纯标点 → 200 total=0；未认证 401；无 `catalog.read` 403；未入驻 403 `1007C0006`；`pageSize=101` → 400 `1000C0001` | B3-AC5/AC6 |
| **T8** | CatalogProductVectorMigrationIntegrationTest | V5 表存在、列/顺序/注释/PK 一致；`flyway_schema_history` V5 success；V1~V4 既有表列零扰动 | B4-AC3 |
| **T9** | CatalogSemanticSearchBenchmarkIntegrationTest | 1k/10k 演示数据（复用 `scripts/benchmark/catalog-seed.sql` 占位参数）；10k 预热后 50 轮 Top10 P95 ≤500ms；1k 记录 P50/P95/max；冷启动首查耗时记录；JSON 落 `target/benchmark/w3102-semantic-bench-*.json` | B5-AC1/AC2 |

**证据项（非单测，验收材料）**：
- **E1 零外呼/零新增依赖**：`common-vector-store` pom 仅 test 依赖；主源零 `java.net`/HTTP/SDK import；断网跑模块与 catalog 测试（Docker 外的纯单测）；→ B1-AC3/B4-AC1；
- **E2 零真实密钥**：本卡零密钥配置/字段；门禁 secretsScan GREEN + 全库核对 → B4-AC4；
- **E3 既有面零改动**：`git diff` 锚定（仅 §1 新增/修改文件）；既有 21 个 catalog 测试文件零改动并全绿（R6 回归）→ B4-AC1/AC2；
- **E4 门禁与覆盖率**：全仓单发 GREEN，ConfigSha256 与基线逐字一致，coverage overall 不劣于 **92.36%**（新模块目标 ≥90%）→ B4-AC3。

红相口径：T1~T9 先写（编译失败/断言失败均计红相留痕），实现后全绿；红相日志落 `build-output/w3102-red-phase.log`（gitignored）。

## §8 边界值与异常行为

| 场景 | 行为 | 依据 |
| --- | --- | --- |
| `q` 缺失 / null / 纯空白 | 400 + `1007C0013` | B3 规则 5 |
| `q` trim 后 >64 字符 | 400 + `1007C0013` | B3 规则 2/5 |
| `q` 仅标点（零向量） | 200 + 空页（total=0） | B1-AC2/B3 规则 6 |
| `categoryCode` null/空白 | 不过滤 | B3 规则 2 |
| `categoryCode` 不在类目树 | 400 + `1007C0013` | B3 规则 5 |
| 候选为空 | 200 + 空页（不再计算向量） | 最小实现 |
| `pageNum`/`pageSize` 越界 | `1000C0001`（400，PageQuery 既有语义） | ADR-005 |
| 未认证 / 无 `catalog.read` / 未入驻 | 401 / 403 / 403 `1007C0006` | B3 规则 5；R6 同口径 |
| 主体服务不可用 | 503 `1007S0001` | `CatalogAccessGuard` 既有语义 |
| 向量缺失 / 源哈希不一致 / 维度不一致 | 重算并回写（不报错） | B2 规则 2 |
| 向量字节损坏（长度非 4 倍数） | 视为缺失，重算回写 | §4 |
| 产品无关联 dataset 或标签为空 | 标签按空串参与向量化，仍可检索 | 最小实现 |
| 已下架/已注销/未上架 | 不出现（`status='LISTED'` 硬过滤） | B2 规则 3 |
| 无相似度阈值 | `total` = 全部候选产品数，按相似度排序后分页；阈值/截断归 V1.5 | B3 规则 4 |
| 并发首查同一产品 | 幂等 upsert，最后写入者胜 | §6 |
| 10k 规模 | O(n) 扫描；基准记录；不宣称生产性能 | B5 边界 |

## §9 向量库选型 POC（Q2-A；编码段基准回填）

| 维度 | **MySQL 8 + Java 余弦（V1.0 选择）** | PostgreSQL + pgvector | Milvus |
| --- | --- | --- | --- |
| 新增中间件/运维 | **0（随主库）** | +1 DB（备份/监控/迁移） | +独立集群（2G+ 内存） |
| 规模上限 | 演示/万级（O(n) 扫描） | 千万级（原生向量索引） | 十亿级（分布式） |
| 演示资源 | **0** | +1 容器 | 显著增加 |
| 性能 | 1k/10k 基准实测（编码段回填） | 原生索引（未实测） | 原生索引（未实测） |
| 信通院生态贴合 | 一般 | 一般 | 好 |
| 结论 | **V1.0 首版（Q2-A）**：零新增组件、演示可用；MySQL 默认 `search` = 加载 + Java 余弦；切换 = 新增 `VectorStore` 适配器（可覆写 `search` 用原生索引）+ 改配置（ADR-003 §5） | 数据/性能触顶后候选；ADR-003 原定首版介质 | 更大规模候选；本 V1.0 不引入 |

**基准口径（编码段回填数字）**：1k/10k 演示数据、Top10、MockMvc 进程内、资格门桩、串行 50 轮、容器内单机；记录冷启动首查耗时 + 稳态 P50/P95/max；10k P95 目标 ≤500ms（沿目录检索指标口径）；**不宣称生产并发性能**。

## §10 对接点预留（仅签名/路径，不建下游类型）

| 下游 | 挂点 | 本卡交付 | 下游承接 |
| --- | --- | --- | --- |
| 3.10.3 AI 审计/护栏 | 无（语义检索不是模型调用；不产生 AI 调用流水） | 无审计写入 | 未来 RAG/重排调模型时在 3.10.1 网关入口挂审计/护栏 |
| 3.10.4 配置页 | 无配置键（维度为常量） | 零配置面 | 未来阈值/维度若可配置另立 |
| V1.5 C-11.2 | `EmbeddingProvider`（真实 embedding 适配器）/ `VectorStore`（pgvector/Milvus 适配器，覆写 `search` 用原生索引） | 两接口 + 本地实现 + 端点契约 | 真实语义模型、原生向量索引、RAG/重排/生成/知识库 |
| 前端 | `GET /api/v1/catalog/semantic-search` | 路径与响应契约 | 未来界面经 Vite 代理 `/api/v1/catalog` 调用；本卡零前端改动 |
| 网关 | 无 | 零改动 | `/api/v1/catalog/**` 网关暴露为存量缺口，另立包 |

## §11 验证命令（编码段交付口径）

1. 模块：`mvn -B -ntp -pl common/vector-store test` + `mvn -B -ntp -pl common/vector-store checkstyle:check`；
2. catalog：`mvn -B -ntp -pl services/catalog-service test`（Testcontainers MySQL 8；无 Docker 整类跳过）+ `checkstyle:check`；
3. 全仓：`mvn -B -ntp compile` / `test` / `checkstyle:check`（既有面回归）；
4. 门禁：`scripts/gates/run-gates.ps1` 单发（运行期仓库静止、禁并行 mvn/npm；ConfigSha256 逐字核验）；
5. 基准：运行 `CatalogSemanticSearchBenchmarkIntegrationTest`，结果 JSON 落 `target/benchmark/`，数字摘要入交付说明；
6. 零消耗实证：pom/依赖清单、主源 import 扫描、断网测试（E1）；全库密钥扫描（E2）；diff 锚定（E3）。

## §12 勘误位（评审/复审段回填）

（空——V1.0 定稿前无勘误；ADR-003 §7 勘误随定稿确认批准）

## §13 确认记录

| 版本 | 日期 | 确认人 | 结论 |
| --- | --- | --- | --- |
| Q1~Q6 | 2026-11-17 | 编排师 | "确认 Q1~Q6 均采 A" = 均采建议口径 A |
| V0.9 | 2026-11-17 | 编排师 | 设计段草案（Q1~Q6 采 A 后落盘） |
| **V1.0** | 2026-11-17 | 编排师 | **定稿一次确认**（"定稿确认，三份转V1.0"）= 编码契约即时生效（与规格 V1.0 / lofi V1.0 一并批准；ADR-003 §7 勘误同步批准） |