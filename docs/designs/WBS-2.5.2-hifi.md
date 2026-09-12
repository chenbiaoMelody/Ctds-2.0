# WBS-2.5.2 一键部署工具链 v1 —— 高保真设计（编码契约）

> 状态：**已确认（2026-09-12，PO 两级设计一次确认"确认进入编码"，AskUserQuestion 即时选定留痕）——本清单即编码契约**
> 上游：WBS 2.5.2 任务行（"部署脚本 + runbook，开发/测试环境演练通过"）+ ADR-012（镜像命名）+ ADR-013（模板与注入口径）+ 2.5.1 登记项五条
> 低保真：`docs/designs/WBS-2.5.2-lofi.md`（六判定 D1~D7）

## 一、行为清单（Given/When/Then，业务可读）

### A. 一键部署脚本 `scripts/deploy/deploy.ps1`

| 编号 | 行为 | Given/When/Then |
| --- | --- | --- |
| A1 | 环境自检 | Given 本机；当脚本启动；则依次自检 docker 可用、kubectl 可用、**集群可达**（`kubectl version` 带 32s 超时探测）、两个 ctds 镜像已在本地（tag 由 `image-tag.ps1` 现算现查）、deploy/k8s 模板含占位符 `image:` 行恰 2 处；任一缺失 → 报**业务可读**错误（缺什么、怎么办、给出下一条命令）并退出码 2 |
| A2 | 注入 | Given 自检通过；当执行注入；则把 `deploy/k8s/` 整目录复制到 `build-output/deploy/<时间戳>/k8s/`，在**副本**上逐行扫描：仅"`image:` 字段 + 值恰为 `IMAGE_PLACEHOLDER`"的行被替换为对应模块 canonical tag（backend-deployment.yaml → example-service tag；frontend-deployment.yaml → frontend tag）；**每个文件替换数必须恰好 1，否则报错退出**；工作区原文件零改动 |
| A3 | NodePort patch（副本内） | Given 注入副本；当处理 Service；则在副本内给两份 Service 打 NodePort patch（backend 30080 / frontend 30081）；`deploy/k8s/` 原模板（ClusterIP）不动 |
| A4 | OpenAPI schema 校验（登记项⑤） | Given 注入副本就绪；当部署前；则先 `kubectl apply --dry-run=server -k <副本目录>`——真实 API 服务端 schema 校验（兑现 2.5.1 hifi K5 顺延项）；失败 → 业务可读报错退出 |
| A5 | 部署与就绪等待 | Given schema 校验通过；当执行部署；则 `kubectl apply -k <副本目录>` 后 `kubectl rollout status`（Deployment ×2，超时默认 180s）；超时/失败 → 打印 Pod 事件摘要（`kubectl describe`/`get pods` 输出）并退出码 1 |
| A6 | 冒烟验证 | Given rollout 完成；当冒烟；则 curl `http://localhost:30081`（前端 200）+ `http://localhost:30081/login`（SPA 深链接 200）+ `http://localhost:30080/`（后端有 HTTP 应答，404 属正常=服务在应答）；任一失败退出码 1 |
| A7 | 业务可读报告 | Given 全流程结束；当落盘；则写 `build-output/deploy/<时间戳>/deploy-report.md`（自检/注入/校验/部署/冒烟各步骤结果 + 镜像 tag + 访问入口），结尾 `[DEPLOY] PASS` 或 `[DEPLOY] FAIL`；stdout 同步关键进展（Write-Host） |
| A8 | 一键清理 | Given 集群有本工具链部署的资源；当以 `-Teardown` 运行；则 `kubectl delete -k <最新副本目录>`（或按 -k 目录参数）删除全部资源并打印清理结果；退出码 0/1/2 同口径 |

### B. frontend 非 root 硬化（登记项①）

| 编号 | 行为 | Given/When/Then |
| --- | --- | --- |
| B1 | 容器非 root | Given 新 frontend 镜像；当 `docker inspect` 查 User 且容器内执行 `id`；则 User=101（nginx），进程非 root |
| B2 | 监听 8080 | Given 容器启动；当探测端口；则 nginx 监听 **8080**（非特权端口），80 不再监听 |
| B3 | 可写路径收敛 /tmp | Given nginx 配置；当主进程写 pid/临时目录；则全部落在 `/tmp`（镜像内唯一保证非 root 可写的约定位置） |
| B4 | SPA fallback 保持 | Given 2.5.1 已验收行为；当访问 `/login` 等深链接；则 200 返回 index.html（`try_files ... /index.html` 不回归） |
| B5 | 模板同步 | Given 端口变更；当部署到 K8s；则 frontend-deployment.yaml containerPort/探针=8080、Service targetPort=8080、补 `runAsNonRoot: true` 与 backend 对齐 |

### C. label 统一（登记项②）

| 编号 | 行为 | Given/When/Then |
| --- | --- | --- |
| C1 | 部署对象名统一 | Given 两份 Deployment；当核对命名；则资源名、`app.kubernetes.io/name`、容器名、selector 四处一致：`ctds-backend` / `ctds-frontend` |
| C2 | 制品名解耦 | Given 镜像名契约；当注入；则按映射表衔接：`example-service → backend-deployment.yaml → ctds/example-service:<tag>`、`frontend → frontend-deployment.yaml → ctds/frontend:<tag>`（映射表在脚本头部显式声明） |

### D. 文档（runbook + 留痕）

| 编号 | 行为 |
| --- | --- |
| D1 | `deploy/runbook.md`：业务可读五节——前提（工具/集群/镜像从哪来）→ 一条命令部署 → 怎么看结果（报告路径+浏览器入口）→ 常见失败对照表（对应 §三边界值）→ 清理（-Teardown + 可选删镜像） |
| D2 | `scripts/deploy/README.md`：命令用法表（同 pipeline/gates README 风格） |
| D3 | ADR-013 补正说明：frontend 端口 80→8080（非 root 硬化）、注入工具链已落地（口径=行级锚定+副本注入）、label 统一口径；原文不动以补正标注 |
| D4 | `deploy/k8s/README.md` 同步（端口/label/runAsNonRoot/NodePort 演练口径）；dependencies.md **无新增**（零新依赖） |

## 二、接口契约表

### deploy.ps1 参数与退出码

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `-RepoRoot` | 脚本上级目录推导 | 仓库根 |
| `-RolloutTimeoutSeconds` | 180 | rollout status 超时 |
| `-Teardown` | 关 | 一键卸载（delete -k） |
| 退出码 | — | 0=全流程 PASS；1=部署/冒烟失败；2=环境自检失败 |

### 依赖的既有接口（复用，不改）

| 接口 | 来源 | 用法 |
| --- | --- | --- |
| `image-tag.ps1 -ModuleName <m>` | scripts/pipeline（ADR-012） | stdout 单行 tag，脚本内调用两次（example-service / frontend） |
| `kubectl apply/delete -k`、`rollout status` | kubectl v1.36.1 | 副本目录为作用域 |
| `build-output/`（gitignore） | 2.2.6 契约 | 副本与报告落点，`build-output/deploy/<时间戳>/` |

### 交付物落点

`scripts/deploy/{deploy.ps1,README.md}`、`deploy/{runbook.md,k8s/*.yaml(改)}`、`frontend/{Dockerfile,nginx.container.conf}(改)`、`docs/adr/ADR-013(补正)`、`docs/designs/WBS-2.5.2-{lofi,hifi}.md`。

## 三、边界值与异常行为

| 场景 | 预期 |
| --- | --- |
| 集群不可达 / kubectl 缺失 | 自检报错退出 2，业务可读提示（含"如未开启 Docker Desktop 内置 K8s，看 runbook 前提节"） |
| 镜像缺失（本地无对应 tag） | 自检报错退出 2，附构建指引两条命令（nightly → docker build） |
| 模板中 `image:` 行 0 处或 ≥2 处占位符 | 注入阶段报错退出 1（模板被误改早失败，防呆） |
| 占位符字面量出现在注释/其他字段 | **不被替换**（行级锚定 image: 字段+值双条件）——评审教训回归用例 |
| rollout 超时（如镜像拉取失败 ImagePullBackOff） | 退出 1 + Pod 事件摘要（沿用 2.5.1 hifi B-7 口径：失败显形在调度侧） |
| NodePort 30080/30081 被宿主占用 | apply 本身不受影响（集群内端口），冒烟 curl 失败 → 报错含端口冲突排查提示 |
| 重复执行 deploy | 幂等：apply 覆盖同名资源，换新时间戳副本目录，冒烟重跑 |
| -Teardown 在无部署时执行 | delete 报 NotFound 不视为失败，提示"无可清理资源"退出 0 |

## 四、实测与演练计划（=WBS"开发/测试环境演练通过"口径）

1. 开启 Docker Desktop 内置 Kubernetes（一次性环境操作，留痕日志；重启 Docker Desktop 前确认无业务容器）；
2. 镜像就绪：复用本机既有产物重打干净 tag（`mvn package -DskipTests` + `npm run build` + `docker build` ×2，tag 用 image-tag.ps1 输出，消除 2.5.1 的 -dirty 后缀）；
3. 单箱预验：frontend 新镜像 docker run 冒烟（B1~B4 逐条实测，含 id 双证非 root）；
4. 全流程演练：deploy.ps1 一键（A1→A7 全链）+ 浏览器实测 NodePort 入口；
5. 幂等与清理：重复执行 deploy → 幂等；-Teardown → 清空；`kubectl get all` 确认零残留；
6. 全量门禁复跑 GREEN（secretsScan 覆盖新脚本）。

## 五、验收演示建议（业务可读，供剧本更新）

> 新增剧本节点"一键部署演示"：编排师执行 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy\deploy.ps1` → 约 2~3 分钟后结尾见 `[DEPLOY] PASS` → 浏览器开 `http://localhost:30081`（平台前端）与 `http://localhost:30081/login`（深链接）→ `http://localhost:30080/`（后端应答）→ 演示完执行 `-Teardown` 清场。既有剧本节点无需修改。

## 六、状态

| 节点 | 结果 |
| --- | --- |
| PO 确认（= 编码契约） | **已确认（2026-09-12，PO 两级设计一次确认"确认进入编码"，AskUserQuestion 即时选定留痕）** |

## 七、实测补正说明（编码契约签署后演练中的实测发现，历史正文不改）

1. **镜像分钟级 tag 回退**（对 A1 的边界补强）：`image-tag.ps1` 的 tag 含分钟时间戳，脚本现算 tag 与镜像实际 tag 可能差一分钟——自检在精确查不到时，回退认领"同仓库 + 同 git 短哈希（先剥 `-dirty` 后缀再解析）"的最新本地 tag，认领过程打印留痕；
2. **新增 A4-pre 镜像装载步骤**（对 A4/A5 的前置补强）：新版 Docker Desktop 内置集群为 **kind 模式**（独立 containerd），不自动可见 docker 守护进程的本地镜像——不装载则全体 Pod `ImagePullBackOff`（实测根因）；步骤 = `docker save` 管道进节点 `ctr --namespace k8s.io images import`，导入后 `images ls` 验证；
3. **backend 镜像 USER 数字化**（对 2.5.1 遗留镜像的修订）：`runAsNonRoot: true` 下 kubelet 只认**数字 uid**，镜像 `USER appuser`（用户名）触发 `CreateContainerConfigError`（实测根因）；Dockerfile 改 `USER 1001`，ADR-013 uid 1001 口径不变；
4. **A6 冒烟通道改分离式 port-forward**（对 D6/A6 的实测修订）：kind 模式集群**不把 NodePort 映射到宿主 localhost**（NodePort patch 保留，对映射 NodePort 的集群仍有效）——冒烟与访问入口改由脚本拉起的**分离式 kubectl port-forward**（30081→80、30080→8080）提供，脚本退出后存活供浏览器访问，`-Teardown` 按 `port-forward.pids` 统一回收；
5. 演练链为以上补正的完整实测路径：run1 自检拦截（tag 分钟差）→ run2 暴露 -dirty 哈希解析错 + 旧镜像 rollout 失败 → run3 暴露 kind 镜像隔离 → run4 暴露 USER 用户名校验错 → run5 暴露 NodePort 不映射 → 修复后全链 PASS（见开发日志终态验证）。
