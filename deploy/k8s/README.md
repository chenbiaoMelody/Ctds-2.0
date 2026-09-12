# C-TDS Kubernetes 编排模板（WBS 2.5.1，规范载体 ADR-013；WBS 2.5.2 修订）

本目录是"机房摆放图纸"：声明两个容器化模块在 Kubernetes 上怎么跑（副本数、探针、资源基线、服务发现）。**模板本身不含镜像名**——镜像字段是占位符 `IMAGE_PLACEHOLDER`，由一键部署工具链（`scripts/deploy/deploy.ps1`，WBS 2.5.2）按 ADR-012 命名规范（`scripts/pipeline/image-tag.ps1` 输出）在**副本目录**上行级注入后执行 `kubectl apply`，本目录文件保持零占位符外的原始状态。

## 模板清单

| 文件 | 内容 |
| --- | --- |
| `backend-deployment.yaml` | ctds-backend（Java 后端）Deployment：1 副本、非 root（镜像 uid 1001）、tcpSocket 8080 探针、资源基线 256Mi/250m ~ 1Gi/1000m |
| `backend-service.yaml` | 后端 ClusterIP Service（8080） |
| `frontend-deployment.yaml` | ctds-frontend（nginx 静态）Deployment：1 副本、**非 root（uid 101，监听非特权端口 8080，WBS 2.5.2 硬化）**、tcpSocket 8080 探针、资源基线 64Mi/50m ~ 256Mi/500m |
| `frontend-service.yaml` | 前端 ClusterIP Service（80 → targetPort 8080） |
| `kustomization.yaml` | Kustomize 资源清单：`kubectl kustomize .` 离线结构校验；注入工具链在副本目录上 `kubectl apply -k` |

## 使用前提与边界（业务可读）

- **镜像须先存在**：后端箱子需先 `mvn -B -ntp package -DskipTests` 再 `docker build`（见 `services/example-service/Dockerfile` 头注）；前端箱子需先 `npm run build` 再 `docker build`（见 `frontend/Dockerfile`）。产物不在位时 docker build 会明确报错。
- **占位符未注入不可直接 apply**：`IMAGE_PLACEHOLDER` 不是合法镜像名，K8s 会拒绝调度——这是防呆边界（hifi B-7）。注入用 `scripts/deploy/deploy.ps1`（2.5.2），口径 = 行级锚定 image: 字段 + 副本目录（本目录不被改写）。
- **部署对象名与镜像名解耦**：模板内统一 `ctds-backend` / `ctds-frontend`（WBS 2.5.2 label 统一），镜像名按 ADR-012（`ctds/example-service`、`ctds/frontend`），由注入脚本映射表衔接。
- **探针用 tcpSocket 而非 HTTP**：后端业务端点有鉴权（未认证 401），HTTP 探针会假红；tcpSocket 只验证"端口在监听"，够用且无假信号。
- **中间件不在模板内**：MySQL/Redis 等编排属后续任务；后端默认 profile 无库自足启动（WBS 2.4.10 B3 决策），模板按此口径。
- **本模板是 base 口径（ClusterIP）**：开发/测试环境的 NodePort 访问（30080/30081）由部署工具链在副本目录内 patch，本目录不落 NodePort；生产口径（Ingress 等）属后续任务。
- 模板已通过 kubectl 客户端侧离线校验；集群真实部署演练由 2.5.2 一键工具链承担（runbook 见 `deploy/runbook.md`）。
