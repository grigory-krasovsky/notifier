# Деплой (CI/CD)

Пайплайн: `.github/workflows/deploy.yml`. На каждый push в `master`:

1. **test** — `./mvnw test` на раннере (Testcontainers использует Docker раннера).
2. **build-and-push** — сборка Docker-образа и push в GHCR: `ghcr.io/grigory-krasovsky/notifier:latest` (+ тег по SHA).
3. **deploy** — по SSH на VPS: копирует `docker-compose.prod.yml`, логинится в GHCR эфемерным `GITHUB_TOKEN`, `docker compose pull` + `up -d`, разлогинивается и чистит старые образы.

БД (`notifier-db`) переживает деплой — данные в volume `pgdata`, пересоздаётся только `notifier-app`.

## Что нужно настроить один раз

### 1. GitHub Secrets

Repo → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Значение |
|---|---|
| `SSH_HOST` | `178.104.232.12` |
| `SSH_USER` | `amnezia` |
| `SSH_PASSWORD` | пароль сервера (**смени текущий — он засветился**) |

`GITHUB_TOKEN` добавляется автоматически, отдельно не нужен.

### 2. Подготовка сервера (один раз, под пользователем `amnezia`)

```bash
# Docker + compose plugin (если ещё нет)
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"      # затем перелогиниться, чтобы docker работал без sudo

# Каталог приложения и секреты (compose-файл принесёт CI)
mkdir -p ~/notifier
cat > ~/notifier/.env <<'EOF'
TELEGRAM_BOT_TOKEN=<токен бота>
DB_PASSWORD=<надёжный пароль БД>
EOF
chmod 600 ~/notifier/.env
```

`.env` живёт только на сервере, в git и в секретах его нет. `docker-compose.prod.yml` присылает CI при каждом деплое (шаг scp) — вручную создавать не нужно.

### 3. Первый запуск

Пуш в `master` → пайплайн соберёт образ и задеплоит. Проверка на сервере:

```bash
cd ~/notifier
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f app
```

## Заметки

- VPS, скорее всего, имеет прямой доступ к `api.telegram.org` (в отличие от локальной машины за российским провайдером), поэтому `TELEGRAM_PROXY_*` на сервере не нужны.
- Образ в GHCR приватный; сервер тянет его, логинясь эфемерным токеном раннера — публичным пакет делать не обязательно.
- Порт БД наружу не открыт; порт приложения 8080 биндится только на `127.0.0.1` (бот работает через long polling, входящие соединения ему не нужны).
