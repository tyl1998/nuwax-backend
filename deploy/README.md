# 单容器部署

## 推荐发布流程

正式发布推荐由 Jenkins 完成：

1. 拉取代码。
2. 使用固定 JDK 17 与 Maven 版本执行后端构建。
3. 使用 `Dockerfile.runtime` 将构建出的 jar 打进镜像。
4. 推送镜像到镜像仓库。
5. 部署平台或节点拉取新镜像并替换旧容器。
6. 通过 `/health` 与 `/ready` 验证发布结果。

## Jenkins 构建命令

```bash
IMAGE_NAME=${IMAGE_NAME} IMAGE_TAG=${IMAGE_TAG} PUSH_IMAGE=true scripts/build-runtime-image.sh
```

## 部署脚本

构建完成后，可用脚本重启同名容器并做 `/ready` 健康检查：

```bash
IMAGE_NAME=${IMAGE_NAME} IMAGE_TAG=${IMAGE_TAG} CONTAINER_NAME=nuwax-backend scripts/deploy-runtime-container.sh
```

常用参数：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CONTAINER_NAME` | `nuwax-backend` | 容器名 |
| `APP_PORT` | `8080` | 容器内 HTTP 端口 |
| `HOST_APP_PORT` | `8080` | 宿主机 HTTP 端口 |
| `OUTER_PORT` | `6443` | 容器内 reverse 外部端口 |
| `HOST_OUTER_PORT` | `6443` | 宿主机 reverse 外部端口 |
| `PULL_IMAGE` | `false` | 部署前是否拉取镜像 |
| `JWT_DIR` | `$(pwd)/docker/jwt` | JWT 文件目录 |
| `UPLOAD_DIR` | 空 | 上传文件挂载目录 |
| `NETWORK_NAME` | 空 | 可选 Docker 网络；默认不指定网络 |
| `NETWORK_ALIAS` | 空 | 可选网络别名，仅设置 `NETWORK_NAME` 时生效 |
| `HEALTH_URL` | `http://localhost:${HOST_APP_PORT}/ready` | 健康检查地址 |

## 部署脚本运行模式

### 新模式：外部依赖地址注入

适用于公司平台后续把 MySQL、Redis、Milvus、ES、MCP、rcoder 等基础服务拆成独立服务后部署。该模式不依赖 compose 网络。

```bash
IMAGE_NAME=nuwax-backend \
IMAGE_TAG=${IMAGE_TAG} \
CONTAINER_NAME=nuwax-backend \
MYSQL_HOST=${MYSQL_HOST} \
MYSQL_PORT=${MYSQL_PORT} \
MYSQL_DATABASE=agent_platform \
MYSQL_USER=${MYSQL_USER} \
MYSQL_PASSWORD=${MYSQL_PASSWORD} \
REDIS_HOST=${REDIS_HOST} \
REDIS_PORT=${REDIS_PORT} \
REDIS_PASSWORD=${REDIS_PASSWORD} \
MILVUS_URI=${MILVUS_URI} \
ES_URL=${ES_URL} \
MCP_PROXY_URL=${MCP_PROXY_URL} \
CODE_EXECUTE_URL=${CODE_EXECUTE_URL} \
BUILD_SERVER_URL=${BUILD_SERVER_URL} \
AI_AGENT_URL=${AI_AGENT_URL} \
DOCKER_PROXY_URL=${DOCKER_PROXY_URL} \
LOG_SERVICE_URL=${LOG_SERVICE_URL} \
CORS_ALLOW_ORIGIN=${CORS_ALLOW_ORIGIN} \
scripts/deploy-runtime-container.sh
```

本地使用已映射到宿主机端口的基础服务时，可使用默认值直接启动：

```bash
IMAGE_NAME=nuwax-backend IMAGE_TAG=local CONTAINER_NAME=nuwax-backend scripts/deploy-runtime-container.sh
```

默认依赖地址：

- MySQL：`host.docker.internal:13306`
- Redis：`host.docker.internal:16379`
- Milvus：`http://host.docker.internal:19530`
- Elasticsearch：`http://host.docker.internal:9200`
- log-platform：`http://host.docker.internal:8097`
- mcp-proxy：`http://host.docker.internal:8020`
- rcoder：`http://host.docker.internal:60000/8086/8088/8099`

### 旧模式：兼容 compose 网络

仅用于本地横向对比或过渡期调试。该模式显式加入旧 compose 网络，并使用 compose 服务名访问依赖。（不推荐在生产环境使用，且依赖的还是旧的基础服务）

```bash
IMAGE_NAME=nuwax-backend \
IMAGE_TAG=test \
CONTAINER_NAME=nuwax-backend-test \
NETWORK_NAME=docker_agent-network \
NETWORK_ALIAS=backend \
MYSQL_HOST=mysql \
MYSQL_PORT=3306 \
MYSQL_DATABASE=agent_platform \
MYSQL_USER=agent_platform \
MYSQL_PASSWORD=admin123 \
REDIS_HOST=redis \
REDIS_PORT=6379 \
REDIS_PASSWORD=123456 \
REDIS_DB=0 \
MILVUS_HOST=milvus \
MILVUS_PORT=19530 \
MILVUS_URI=http://milvus:19530 \
MILVUS_USER=root \
MILVUS_PASSWORD=Milvus \
DORIS_HOST=mysql \
DORIS_PORT=3306 \
DORIS_DB=agent_custom_table \
DORIS_USERNAME=agent_platform \
DORIS_PASSWORD=admin123 \
ES_URL=http://elasticsearch:9200 \
ES_USERNAME=elastic \
ES_PASSWORD=elastic123 \
LOG_SERVICE_URL=http://log_platform:8097 \
MCP_PROXY_URL=http://mcp-proxy:8089 \
CODE_EXECUTE_URL=http://mcp-proxy:8089/api/run_code_with_log \
BUILD_SERVER_URL=http://rcoder:60000/api \
AI_AGENT_URL=http://rcoder:8086 \
DOCKER_PROXY_URL=http://rcoder:8088 \
DEV_SERVER_HOST=http://rcoder \
PROD_SERVER_HOST=http://rcoder \
CORS_ALLOW_ORIGIN=http://localhost,http://localhost:80,http://localhost:8080 \
scripts/deploy-runtime-container.sh
```

如果旧 compose 后端占用了 `8080/6443`，需要先停止：

```bash
docker stop docker-backend-1
```

## 构建脚本参数

`scripts/build-runtime-image.sh` 支持以下环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `IMAGE_NAME` | `nuwax-backend` | 镜像名 |
| `IMAGE_TAG` | `local` | 镜像 tag |
| `MAVEN_PROFILE` | `prod` | Maven profile |
| `SKIP_TESTS` | `true` | 是否跳过测试 |
| `SKIP_MAVEN_BUILD` | `false` | 是否跳过 Maven 构建，只打镜像 |
| `PUSH_IMAGE` | `false` | 是否推送镜像 |
| `PLATFORM` | 空 | Docker build 平台，例如 `linux/amd64` |

## 本地快速验证

```bash
scripts/build-runtime-image.sh
```

只重新打镜像：

```bash
SKIP_MAVEN_BUILD=true scripts/build-runtime-image.sh
```

## 全量 Docker 构建

`Dockerfile` 保留为 CI 备用全量构建方案，已使用 BuildKit Maven 缓存，但不建议部署节点现场执行：

```bash
DOCKER_BUILDKIT=1 docker build -t nuwax-backend:full .
```

## 准备环境变量

```bash
cp deploy/.env.sample deploy/.env
```

至少需要修改：

- `DB_HOST`
- `DB_NAME`
- `DB_USERNAME`
- `DB_PASSWORD`
- `REDIS_HOST`
- `MILVUS_URI`
- `JWT_SECRET_KEY`
- `CORS_ALLOW_ORIGIN`
- `CORS_ALLOW_CREDENTIALS`

## 本地 JWT 文件

本地验证时 JWT 密钥文件放在：

```bash
docker/jwt/jwt_secret_key.txt
```

当前临时允许该文件随仓库/构建上下文提供，后续需要改为通过公司平台 Secret / 环境变量注入 `JWT_SECRET_KEY`。

## 本地 compose 验证

`deploy/docker-compose.yml` 仅用于本地验证单容器后端，不作为正式部署方式。

```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

## 验证

```bash
curl http://localhost:8080/health
curl http://localhost:8080/ready
```

`/health` 用于存活检查；`/ready` 会检查 MySQL、Redis、Milvus TCP 连通性。
