# 一键部署工具链（WBS 2.5.2，规范载体 ADR-012/013）

本目录是"上架电梯"：一条命令把两个镜像部署到开发/测试 Kubernetes 集群并验证可访问。镜像名来源始终是 ADR-012 契约（`scripts/pipeline/image-tag.ps1`），注入方式沿 ADR-013 口径——**在本目录产出的副本目录上行级注入，仓库模板文件零改动**。

## 日常使用（业务可读）

| 你想做什么 | 命令（仓库根目录执行） | 看什么 |
| --- | --- | --- |
| 一键部署到集群 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy\deploy.ps1` | 结尾输出 `[DEPLOY] PASS`；报告在 `build-output/deploy/<时间戳>/deploy-report.md` |
| 一键清场（卸载） | `powershell ... scripts\deploy\deploy.ps1 -Teardown` | 输出 `[DEPLOY] PASS (teardown)` |

- 退出码：0=全流程通过，1=部署/冒烟失败（报告含失败步骤与错误摘录），2=环境自检失败（缺什么一次性列全，附下一步指引）。
- 部署成功后访问入口：前端 `http://localhost:30081`（SPA 深链接如 `/login` 同样 200）、后端 `http://localhost:30080`（`/` 返回 404 属正常=服务在应答）。
- 幂等：重复执行直接覆盖同名资源，每轮生成新的时间戳副本目录。

## 脚本内做了什么（六步）

1. 环境自检：docker / kubectl / 集群可达 / 两镜像在本地 / 模板占位符恰 2 处 / curl.exe；
2. 副本注入：复制 `deploy/k8s` → `build-output/deploy/<时间戳>/k8s/`，逐行**只替换"image: 字段且值为 IMAGE_PLACEHOLDER"的行**（每文件恰 1 处，多/少即报错）；
3. NodePort patch（仅副本内）：backend 30080 / frontend 30081，base 模板保持 ClusterIP；
4. 服务端 schema 校验：`kubectl apply --dry-run=server`（OpenAPI schema 级，兑现 2.5.1 顺延项）；
5. 部署与就绪等待：`kubectl apply -k` + 双 Deployment `rollout status`（默认 180s 超时）；
6. 冒烟 + 报告：NodePort 入口 HTTP 探测 + 业务可读报告落盘。

## 边界与限制（留痕）

- 目标环境 = 开发/测试（本机 Docker Desktop 内置 K8s 同口径）；生产口径（Ingress/多副本/Helm）属后续任务。
- 脚本不构建镜像：缺镜像时自检报错并给指引（nightly-build.ps1 → docker build，完整命令见 `deploy/runbook.md`）。
- 中间件（MySQL/Redis）不在部署范围（沿 ADR-013 口径）。
