# WBS-2.5.2 一键部署工具链 v1 —— 低保真设计（方向判定）

| 项 | 内容 |
| --- | --- |
| 任务卡 | WBS 2.5.2：部署脚本 + runbook（开发/测试环境演练通过）；依赖 2.5.1（已关闭，main=6e021fb） |
| 设计类型 | 非界面类（工具链），两级一并提交、一次确认（章程 2.6） |
| 上游规范 | ADR-012（镜像命名契约，`scripts/pipeline/image-tag.ps1` 为唯一镜像名来源）、ADR-013（K8s 模板与占位符注入口径） |
| 2.5.1 登记项 | 本任务顺带处理五项：①frontend 非 root 硬化 ②label 统一 ③IMAGE_PLACEHOLDER 注入脚本（按 image: 字段行定位）④集群部署演练 ⑤OpenAPI schema 级校验（2.5.1 hifi K5 顺延项） |

## 一、目标（一句话）

给"已在仓库里的装箱说明书和机房摆放图纸"配一条**一键通道**：一条命令把两个镜像部署到开发/测试 Kubernetes 集群并验证可访问，附一本业务可读的 runbook——让"拉取主线即可部署"成为现实。

## 二、方向判定（七项，D1~D7；初稿标题"六项"系计数笔误，评审①③指出后按补正更正）

### D1 工具链形态与位置

`scripts/deploy/deploy.ps1`（PowerShell，与 nightly-build 同风格同纪律：PS 5.1 坑规避清单已记忆化——原生命令 stderr 经 `cmd /c` 合并、不用管道收尾判退出码、进展输出用 Write-Host）。配套 `deploy/runbook.md`（业务可读：前提 → 一条命令 → 看什么 → 常见失败 → 清理）。
**理由**：与既有 2.2.6/2.5.1 工具链（scripts/pipeline、scripts/gates）同栈同风格，不引入新语言/框架；runbook 落 deploy/ 与模板同目录。

### D2 集群演练目标环境（需 PO 裁决）

| 备选 | 说明 | 取舍 |
| --- | --- | --- |
| **Docker Desktop 内置 Kubernetes（推荐）** | 本机已有 Docker Desktop（29.7.2）+ kubectl v1.36.1，设置里开启内置单节点集群即可，**零新增工具**；开启需重启 Docker Desktop 一次（当前无业务容器运行，影响面为零） | 演练后集群保留供 2.5.3 监控告警基座复用 |
| kind（弃） | 需下载新二进制 + 拉集群镜像，引入非冻结栈工具 | 与 ADR-001"最主流最常见"不冲突但多余——本机已有等价物 |
| 降级为纯离线校验（弃） | 不满足 WBS"开发/测试环境演练通过"口径 | 不可选 |

**注意**：开启 Docker Desktop 内置 K8s 属于本机开发环境操作（非仓库资产变更），将按"最小步骤"执行并留痕于日志。

### D3 注入机制（登记项③，ADR-013 口径落地）

- **行级锚定**：逐行扫描，仅匹配"`image:` 字段行且值为 `IMAGE_PLACEHOLDER`"（锚定字段+值双条件），**禁止全文替换**——2.5.1 评审教训（注释里出现占位符字面量会被误伤）已通过"锚定 image: 行"根除；
- **副本注入，工作区零污染**：把 deploy/k8s 复制到 `build-output/deploy/<时间戳>/k8s/`（gitignore 目录）后仅改副本，沿 ADR-013 弃 overlay 的同一理由（动态 tag 不污染工作区）；
- **每文件恰好 1 处替换**：替换数 ≠1 即报错退出（防呆：模板被误改时早失败）；
- **OpenAPI schema 级校验（登记项⑤，兑现 2.5.1 K5 顺延）**：注入副本先 `kubectl apply --dry-run=server -k` 过一遍（真实 API 的 schema 校验），通过后才真 apply。

### D4 登记项①：frontend 非 root 硬化

现状：`nginx:1.29-alpine` 默认以 root 起主进程（仅 worker 降权）——2.5.1 评审②登记。
**方案**：不引入新基础镜像（nginx-unprivileged 镜像 = 新依赖须走审批），在既有 Dockerfile 内用官方镜像自带 `nginx` 用户（uid 101，gid 101）做**非 root 化三件套**：①监听端口 80→**8080**（非特权端口）；②nginx 主配置收敛可写路径到 `/tmp`（pid、client_body/proxy/fastcgi 等临时目录）；③Dockerfile 末尾 `USER 101`。deploy/k8s 模板同步改（containerPort/探针端口 8080、Service targetPort 8080、补 runAsNonRoot），并补 ADR-013 端口口径留痕。**验收实测**：`docker inspect` + 容器内 `id` 双证非 root，冒烟含 SPA /login 深链接（防改配置破坏 2.5.1 已验收行为）。

### D5 登记项②：label 统一

现状不一致：backend 模板 label `app.kubernetes.io/name=example-service`（=镜像制品名）vs 资源名 `ctds-backend`；frontend 模板用 `ctds-frontend`。
**口径**：**K8s 部署对象名统一 `ctds-backend` / `ctds-frontend`**（资源名、label name、容器名、selector 四处一致），与镜像名（ADR-012 制品名 `ctds/example-service`、`ctds/frontend`）**解耦**，由注入脚本的**映射表**显式衔接：`example-service → backend-deployment.yaml → ctds/example-service`、`frontend → frontend-deployment.yaml → ctds/frontend`。名字分属"部署对象"与"制品"两个空间，各自统一、靠映射表对接，不强行同字面。

### D6 访问口径（v1 = 开发/测试环境）

**NodePort 暴露**：backend 30080 / frontend 30081，浏览器/curl 从 localhost 直达，无需长驻进程——沿"验收演示期间须保活"教训，短进程 port-forward 易断不可靠。实现方式：注入副本目录内对两份 Service 做 NodePort patch（**base 模板保持 ClusterIP 不动**——2.5.1 已验收交付物零回归），副本反正要生成，patch 零额外成本。runbook 写明：生产口径（ClusterIP + Ingress）属后续任务，本工具链 v1 只面向开发/测试环境。

### D7 镜像供给口径

脚本**不内置构建**（最小实现，构建链复用 2.2.6/2.5.1 既有口径）：前置自检发现本地镜像缺失 → 明确报错退出并给出一条命令指引（先 `nightly-build.ps1` 出产物，再按 runbook 的 docker build 命令装箱）。"一键"覆盖"从镜像就绪到部署验证"，构建另有一条命令链，两段在 runbook 里串成完整路径。

## 三、不做的事（边界）

- 不做中间件编排（MySQL/Redis，沿 ADR-013 口径属后续任务）；
- 不做生产环境部署（Ingress/Helm/多副本，v1 只覆盖开发/测试）；
- 不改 nightly/gates 既有脚本；不动 pom/业务代码；
- 不引入任何新依赖（基础镜像、二进制工具均不新增）。

## 四、交付物清单（预期）

| 交付物 | 位置 |
| --- | --- |
| 一键部署脚本 | `scripts/deploy/deploy.ps1` + `scripts/deploy/README.md` |
| 注入能力（脚本内实现，锚定 image: 行） | 同上 |
| runbook（业务可读） | `deploy/runbook.md` |
| frontend 非 root 化 | `frontend/Dockerfile`、`frontend/nginx.container.conf`（改） |
| K8s 模板修订（非 root 端口/label 统一/runAsNonRoot） | `deploy/k8s/*.yaml` + README（改） |
| ADR-013 补正留痕（端口变更 + 注入工具链落地口径） | `docs/adr/ADR-013-*.md`（补正说明） |

## 五、状态

| 节点 | 结果 |
| --- | --- |
| PO 确认（低保真方向） | **已确认（2026-09-12，PO 两级设计一次确认"确认进入编码"，AskUserQuestion 即时选定留痕）** |
