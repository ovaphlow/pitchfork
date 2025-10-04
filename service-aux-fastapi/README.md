# aux-fastapi

Simple internal-only file upload manager built with FastAPI.

Endpoints:
- POST /upload (header: X-API-Key)
- GET /files (header: X-API-Key)
- GET /files/{id}/download (header: X-API-Key)
- DELETE /files/{id} (header: X-API-Key)

See `.env.example` for configuration. By default the project now expects a Postgres database.

Configuration options:
- Set `DATABASE_URL` to a SQLAlchemy-compatible Postgres URL (e.g. `postgresql+psycopg2://user:pass@host:5432/db`).
- Or set `PG_USER`/`PG_PASSWORD`/`PG_HOST`/`PG_PORT`/`PG_DB` and the app will build the `DATABASE_URL` for you.
#

##

message, bulletin

## 使用 uv 管理包与虚拟环境（开发）

建议在开发环境使用 Astral 的 `uv` 来管理依赖与项目虚拟环境。快速步骤：

- 安装 uv（在 macOS / Linux）：

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

- 本仓库的 Python 服务已经包含 `pyproject.toml`。在服务目录中运行：

```bash
cd service-aux-fastapi
# 同步并创建项目虚拟环境（会生成 .venv）：
uv sync
# 生成或更新锁文件（uv.lock）并提交到仓库：
uv lock
```

- 直接运行开发服务器（uv 会确保依赖已安装在项目环境中）：

```bash
uv run uvicorn app.main:app --host 0.0.0.0 --port 8432
```

- 如果你需要保持 `requirements.txt`（例如给 Docker 构建使用），可以用 uv 导出或编译：

```bash
# 从 pyproject/uv.lock 生成平台无关的 requirements.txt：
uv pip compile --universal --output-file requirements.txt
```

注意：首次切换到 `uv` 后，运行 `uv lock` 并把 `uv.lock` 提交到版本控制，以确保所有开发者使用一致的、可重现的依赖集合。

## 在中国大陆使用 PyPI 镜像

如果你在中国大陆开发或在 CI / 构建环境中构建镜像，可以使用国内 PyPI 镜像来加速依赖下载。常用镜像示例：

- 清华：https://pypi.tuna.tsinghua.edu.cn/simple
- 阿里云：https://mirrors.aliyun.com/pypi/simple/

建议做法：

- 在 Dockerfile 中设置镜像（本仓库示例已将清华镜像写入全局 pip 配置），示例：

	- 设置环境变量：`PIP_INDEX_URL` / `PIP_TRUSTED_HOST`
	- 或在镜像中写入 `/etc/pip.conf`，以保证构建时使用镜像

在项目级 `.env` 中设置 PyPI 源

如果你已在项目的 `.env` 文件中添加了 PyPI 相关环境变量（例如 `PIP_INDEX_URL`、`PIP_TRUSTED_HOST`），可以在准备运行 `uv` / `pip` 命令前把这些变量导出到当前 shell：

```bash
# 把 .env 中声明的变量导出为环境变量（适用于 POSIX shell）
set -a
source .env
set +a

# 之后 uv/pip 会使用你在 .env 中设置的镜像
uv sync
uv lock
```

注意：Dockerfile 已在镜像构建阶段把豆瓣镜像写入 `/etc/pip.conf`，所以容器内构建时会默认使用豆瓣源（`https://pypi.douban.com/simple`）。

- 在本地开发或 CI 中，你可以临时通过环境变量使用镜像：

```bash
export PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
export PIP_TRUSTED_HOST=pypi.tuna.tsinghua.edu.cn
# 然后运行 uv/ pip 命令，例如：
uv sync
# 或
pip install -r requirements.txt
```

或者在用户级别创建一个 `~/.pip/pip.conf`（或 `~/.config/pip/pip.conf`）来持久配置镜像。