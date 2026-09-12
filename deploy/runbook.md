# C-TDS 开发/测试环境部署 Runbook（WBS 2.5.2）

> 面向读者：需要把平台"跑起来看效果"的成员（无需编程知识）。
> 覆盖范围：开发/测试环境（单机 Docker Desktop + 内置 Kubernetes）。生产部署属后续任务。

## 1. 前提（一次性准备）

| 前提 | 怎么确认 | 没有怎么办 |
| --- | --- | --- |
| Docker Desktop 在运行 | 任务栏鲸鱼图标 / `docker version` 有响应 | 启动 Docker Desktop |
| Docker Desktop 内置 Kubernetes 已开启 | `kubectl version` 能看到 `Server Version:` 一行 | Docker Desktop → Settings → Kubernetes → 勾选 "Enable Kubernetes" → 等待重启完成（约几分钟，一次性操作） |
| 构建工具就绪（JDK17/Maven/Node/npm） | `scripts\pipeline\nightly-build.ps1` 能全绿 | 按 nightly 流水线环境自检提示补齐 |
| kubectl 命令可用 | `kubectl version --client` 有输出 | Docker Desktop 设置启用 Kubernetes 时会自动配置 |

## 2. 准备镜像（镜像不存在时才需要）

一键部署脚本**要求两个镜像已在本地**（它只负责"上架"，不负责"装箱"）。镜像不存在时按这两步：

```bat
:: 第一步：完整构建一遍项目（产物：后端 jar + 前端 dist + 镜像名清单）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\pipeline\nightly-build.ps1

:: 第二步：装箱（把 <镜像tag> 换成上一步 build-output\<时间戳>\image-tags.txt 里的对应行）
docker build -t <后端镜像tag> services\example-service
docker build -t <前端镜像tag> frontend
```

提示：最新一次构建的 `image-tags.txt` 里 `ctds/example-service:...` 对应后端、`ctds/frontend:...` 对应前端。跑过部署脚本后，若镜像缺失，脚本自检会直接打印这两个 tag 的准确名字。

## 3. 一键部署

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy\deploy.ps1
```

结尾看到 `[DEPLOY] PASS` 即成功，同时报告落盘：`build-output/deploy/<时间戳>/deploy-report.md`。

## 4. 怎么看结果

| 看什么 | 入口 | 预期 |
| --- | --- | --- |
| 平台前端页面 | 浏览器打开 `http://localhost:30081` | 平台首页正常显示 |
| 深链接不 404 | 浏览器打开 `http://localhost:30081/login` 后刷新 | 页面正常（SPA fallback） |
| 后端在应答 | 浏览器/curl 打开 `http://localhost:30080/` | 404 属正常（服务活着，业务端点有鉴权） |
| 集群里的资源 | `kubectl get pods` | `ctds-backend` / `ctds-frontend` 两个 Running |
| 部署报告 | `build-output/deploy/<时间戳>/deploy-report.md` | 各步骤全 PASS |

> 访问原理（业务可读）：本机集群（Docker Desktop 新版内置 Kubernetes）不会把 NodePort 端口直接开放给浏览器，部署脚本会悄悄拉起两条"转发专线"（kubectl port-forward）并让它们在脚本退出后继续工作——您只管开浏览器；`-Teardown` 会把专线一并收回。

## 5. 常见失败对照表

| 现象（脚本报错） | 原因 | 处理 |
| --- | --- | --- |
| `cluster reachable` 检查失败 | 内置 Kubernetes 未开启或 Docker Desktop 刚重启还没就绪 | 见 §1 第二行；等 1~2 分钟重试 |
| `local image exists` 检查失败 | 镜像没装箱 | 按 §2 两步走 |
| `exactly 1 anchored placeholder line` 报错 | 模板被手工改动过 | 恢复 `deploy/k8s` 原状（git 检出），勿手改模板 |
| rollout 超时 | 集群调度/拉取问题 | 报告尾部有 Pod 列表摘录；`kubectl describe pod` 看事件 |
| 冒烟不通但部署成功 | 本机 30080/30081 端口被其他软件占用 | 换端口占用者退出，或改 NodePorts 后重跑 |
| 重复执行报资源已存在 | 不会——apply 是覆盖式的 | 无需处理（幂等） |

## 6. 清理

```bat
:: 卸载集群内资源并停掉转发专线（不动镜像、不动仓库文件）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy\deploy.ps1 -Teardown

:: 确认清空
kubectl get all
```

镜像如需一并删除：`docker rmi <镜像tag>`。内置 Kubernetes 可保留（后续监控告警任务复用），也可在 Docker Desktop 设置里关闭。

## 7. 监控告警基座怎么看（WBS 2.5.3）

> 前提：平台应用已按 §3 部署（监控看板需要应用数据，告警演练需要应用在跑）。监控栈由独立脚本管理，生命周期与 §3 的应用部署互相独立。

### 一键部署监控

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy\monitoring.ps1
```

结尾看到 `[MONITOR] PASS` 即成功，报告落盘 `build-output/monitoring/<时间戳>/monitoring-report.md`。脚本会顺带做一次**告警演练**：把平台后端临时缩到 0（约 1~2 分钟不可用，仅本机）→ 验证告警真的响了 → 自动恢复。全程无需人工干预。

### 怎么看结果

| 看什么 | 入口 | 预期 |
| --- | --- | --- |
| 平台基础看板 | 浏览器打开 `http://localhost:30082` | 免登录直接看（匿名访客），"服务存活"是绿色"运行中" |
| 部署报告 | `build-output/monitoring/<时间戳>/monitoring-report.md` | 各步骤全 PASS，含演练四步留痕 |
| 集群里的监控资源 | `kubectl -n ctds-monitoring get pods` | prometheus / alertmanager / grafana 三个 Running |
| 告警规则 | `kubectl -n ctds-monitoring get configmap ctds-prometheus-config` | 服务下线 + 错误率两条规则 |

### 排查用（一般成员用不到）

Prometheus / Alertmanager 的管理界面不常驻；需要看时手动拉转发，用完 Ctrl+C：

```bat
kubectl -n ctds-monitoring port-forward svc/ctds-prometheus 9090:9090
kubectl -n ctds-monitoring port-forward svc/ctds-alertmanager 9093:9093
```

### 监控常见失败对照表

| 现象（脚本报错） | 原因 | 处理 |
| --- | --- | --- |
| `platform app deployed` 检查失败 | 平台应用还没部署 | 先跑 §3 一键部署（监控不代部署应用） |
| `local image exists` 检查失败 | 监控镜像没拉下来 | `docker pull prom/prometheus:v3.13.3` 等（镜像加速器不通时换加速域名拉取后 retag，见 ADR-014） |
| 指标冒烟不通 | 后端没带抓取注解或指标端点被改坏 | 检查 `deploy/k8s/backend-deployment.yaml` 的 prometheus.io/* 三条注解是否在 |
| 演示卡在 firing/超时 | 集群负载或时间窗问题 | 重跑脚本（apply 幂等，演练自动重做） |
| 30082/19090/19093 端口占用 | 其他软件占用 | 占用者退出后重跑 |

### 清理监控（不动平台应用）

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy\monitoring.ps1 -Teardown
```
