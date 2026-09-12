# WBS-2.5.1 容器化部署模板 —— 高保真设计（定稿 = 编码契约）

- 承接：`docs/designs/WBS-2.5.1-lofi.md`（方向确认后定稿）
- 交付物清单：`services/example-service/Dockerfile` + `services/example-service/.dockerignore`、`frontend/Dockerfile` + `frontend/.dockerignore` + `frontend/nginx.container.conf`、`deploy/k8s/{backend-deployment,backend-service,frontend-deployment,frontend-service,kustomization}.yaml`、`deploy/k8s/README.md`、`docs/adr/ADR-013-容器化部署模板规范.md`、`docs/dependencies.md`（新增"容器基础镜像"节）。**不改**：pom、业务代码、scripts/gates、scripts/pipeline。
- 状态：**已确认并验收通过（2026-09-12 确认进入编码；同日 PO 业务验收通过，裁决"合并推送"，留痕见 lofi §4 尾注）**

## 1. 业务可读行为清单（逐条，可验收口径）

**后端容器（services/example-service/）**
- B1 Dockerfile：基础镜像 `eclipse-temurin:17-jre`；工作目录 /app；COPY `target/example-service-2.0.0-SNAPSHOT.jar` 为 app.jar；`EXPOSE 8080`；入口 `java -XX:MaxRAMPercentage=75.0 -jar app.jar`（容器内存感知，防吃满 pod 内存）；非 root 运行（专用用户 uid 1001——uid 1000 已被基础镜像默认用户占用，useradd 退 4 实测留痕）。
- B2 .dockerignore：排除 src/、logs/、.mvn 等，build context 仅收 target/*.jar（加速构建、缩小上下文）。
- B3 冒烟：`docker run -d -p 18080:8080` 后容器持续运行（10 秒不退出）、`curl http://localhost:18080/` 有 HTTP 响应（401/404 均算"服务在应答"）；`docker logs` 无 FATAL。

**前端容器（frontend/）**
- F1 Dockerfile：基础镜像 `nginx:1.29-alpine`；COPY `dist/` 至 `/usr/share/nginx/html`；COPY `nginx.container.conf`（SPA 路由 fallback `try_files ... /index.html` + 80 端口 + gzip 基础项）；`EXPOSE 80`。
- F2 .dockerignore：排除 node_modules/、src/、e2e/ 等，仅收 dist/ 与 nginx.container.conf。
- F3 冒烟：`docker run -d -p 18081:80` 后 `curl http://localhost:18081/` 返回 200 且含 HTML；刷新型路由（如 /login）返回 200（SPA fallback 生效）。

**K8s 编排模板（deploy/k8s/）**
- K1 backend-deployment.yaml：replicas 1；image `IMAGE_PLACEHOLDER`（业务可读注释：由 2.5.2 工具链经 image-tag.ps1 注入，命名规范 ADR-012）；tcpSocket 8080 存活+就绪探针（initialDelay 30/10）；resources requests 256Mi/250m、limits 1Gi/1000m；labels 统一 `app.kubernetes.io/part-of=ctds`。
- K2 backend-service.yaml：ClusterIP 8080，选择器对齐 K1。
- K3 frontend-deployment.yaml：replicas 1；image `IMAGE_PLACEHOLDER`；tcpSocket 80 探针；resources requests 64Mi/50m、limits 256Mi/500m。
- K4 frontend-service.yaml：ClusterIP 80。
- K5 kubectl 客户端侧**离线**校验通过：实测 `create --dry-run=client` 需连 API 做资源映射（本机无集群，行不通），改用 `kubectl kustomize deploy/k8s`（配 `kustomization.yaml`，纯离线）——4 资源全识别、退出码 0；**OpenAPI schema 级校验顺延至 2.5.2 集群演练**（留痕）。kustomization.yaml 为新增交付物（主流惯例，2.5.2 可直接 `kubectl apply -k`）。
- K6 deploy/k8s/README.md：业务可读说明——模板是什么、占位符怎么注入（2.5.2）、与 ADR-012/013 的关系。

**通用**
- G1 镜像命名消费 ADR-012：验证时用 `scripts/pipeline/image-tag.ps1 -ModuleName example-service` 的输出作为本地镜像 tag（-dirty 属预期，仅本地验证不入库）。
- G2 基础镜像拉取：直拉 Docker Hub 优先；失败时 `docker pull docker.m.daocloud.io/<path>` 后 `docker tag` 改回规范名（实测留痕，不入库脚本）。
- G3 既有代码零改动；全部门禁照常（新文件进 secretsScan/ESLint 范围需无违规）。

## 2. 接口契约表（对 2.5.2 消费方）

| 契约 | 定义 |
| --- | --- |
| Dockerfile 位置与 context | `services/example-service/`（需 target jar 就位）、`frontend/`（需 dist/ 与 nginx.container.conf 就位） |
| K8s 模板 | `deploy/k8s/*.yaml`；镜像注入点 = `image: IMAGE_PLACEHOLDER` 字段行（每份 Deployment 1 处；注入按 image: 字段行定位，勿全文替换） |
| 端口约定 | 后端容器 8080（Service 同口）、前端容器 80；宿主验证端口 18080/18081（一次性，不入模板） |
| 探针约定 | tcpSocket（后端 8080 / 前端 80），initialDelay 30s/10s（后端 Spring 启动窗） |
| 基础镜像 | eclipse-temurin:17-jre、nginx:1.29-alpine（禁 latest；dependencies.md 基础镜像节 + ADR-013 双登记） |

## 3. 边界值与异常行为

| # | 场景 | 期望行为 |
| --- | --- | --- |
| B-1 | target jar 不存在时 docker build | 构建失败（COPY 报错）——README/ADR-013 载明前置：先跑 mvn package 或 nightly N3 |
| B-2 | dist/ 不存在时前端 docker build | 同上，先 npm run build 或 nightly N4 |
| B-3 | 8080/80 被宿主占用 | 容器内端口不受影响；宿主映射用一次性高位端口验证（18080/18081） |
| B-4 | 基础镜像直拉失败 | G2 镜像源 retag 通道，实测留痕 |
| B-5 | SPA 深链接刷新（/login） | nginx fallback 返回 200（F3 覆盖） |
| B-6 | 后端容器内存超限 | JVM MaxRAMPercentage=75 配合 limits 1Gi，OOM 由 K8s 重启策略兜底（模板注释载明） |
| B-7 | `IMAGE_PLACEHOLDER` 未注入直接 apply | kubectl apply 可提交但 Pod 调度失败（ImagePullBackOff，镜像名非法）——属 2.5.2 注入机制的防呆边界，README 载明（评审②更正：apply 侧不报错，失败在调度侧） |

## 4. 依赖核验与登记（引入前 4 步，章程 3.5）

| 基础镜像 | 版本 | 核验与登记 |
| --- | --- | --- |
| `eclipse-temurin:17-jre` | 17（显式 tag） | Docker Hub 官方仓库（temurin 官方维护，Apache-2.0/GPLv2 with CE 聚合许可，运行 Java 事实标准镜像）；登记 dependencies.md 新增"容器基础镜像"节 |
| `nginx:1.29-alpine` | 1.29 主线稳定（显式 tag，禁 latest/禁 mainline 漂移） | Docker Hub 官方 nginx 镜像（BSD-2-Clause）；同上登记 |

- 无 Maven/npm 新依赖；审批口径：PO 预授权（lofi 问题 3/5 确认记录）。

## 5. 验收剧本更新建议（业务语言）

- 建议增补"容器化模板演示"节点：`mvn -B -ntp package -DskipTests`（或跑一次 nightly）→ `docker build` 两个箱子 → `docker run` 各起一个 → 浏览器开 http://localhost:18081 看到平台前端页面、命令行 curl http://localhost:18080 有响应 → `docker ps` 看两个箱子在运行。既有节点无需修改。

## 6. 映射表（WBS 产出 → 设计 → 验证）

| WBS 2.5.1 产出定义 | 对应设计 | 验证方式 |
| --- | --- | --- |
| 各模块容器化 | B1/B2 + F1/F2（容器化对象口径 lofi #1） | B3/F3 冒烟实测 + G1 tag 契约 |
| K8s 编排模板 | K1–K4 + 契约表 + ADR-013 | K5 离线校验 + README（K6） |
| （衔接）镜像命名契约消费 | G1 | 实测 image-tag 输出作 docker build tag |

## 7. 补正说明（编码与实测/评审处置期）

- **B1 jar 名通配留痕**（评审①P2）：Dockerfile 实际 COPY `target/example-service*.jar`（version-agnostic，与 .dockerignore 白名单同口径），hifi B1 原文写具体版本名——通配方向合理且实测构建通过，此补正即为留痕；Spring Boot 3.5 repack 只产单一 fat jar，无 *-plain.jar 双匹配面（评审③收紧建议评估后维持通配）。
- **uid 1001**（评审①②③P2）：Dockerfile/ADR-013/hifi 均为 uid 1001（uid 1000 被基础镜像默认用户占用，useradd 退 4 实测）；dependencies.md 登记行笔误 uid 1000 已更正并注明原因。
- **K8s 模板评审处置**（评审②③④P3）：backend Deployment 补 `runAsNonRoot: true`（镜像 USER=appuser，安全成立）；limits 补 OOM 兜底注释（①P3 B-6）；两 Deployment 注释改写不再包含 IMAGE_PLACEHOLDER 字面量（防 2.5.2 全文替换误伤），注入口径=按 `image:` 字段行定位（④P3）；frontend 非 root 改造与 securityContext 生产化、label 取值统一（ctds-frontend vs ADR-012 模块名 frontend）**登记 2.5.2 处理**（②③P3 留痕）。
- **K5 校验改道**：`kubectl create --dry-run=client` 需连 API 做资源映射，本机无集群行不通——改 `kubectl kustomize`（kustomization.yaml 为新增交付物），OpenAPI schema 校验顺延 2.5.2 集群演练。
- **评审③P3 不采纳**：两 Deployment 结构同构（45 行/份）不抽 kustomize base/overlay——ADR-013 §2 已有 overlay 弃用决策，4 文件规模抽层反增复杂度。
