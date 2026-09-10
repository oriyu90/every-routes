# Every routes — リポジトリ構成

共通概念は [`00-overview.md`](00-overview.md)、各コンポーネント詳細は [`01-every-routes-server.md`](01-every-routes-server.md) / [`02-every-routes-android.md`](02-every-routes-android.md) を参照。

## 1. 方針：モノレポ + 共通スペック

**1つのリポジトリ `every-routes/`** に、サーバー・Android アプリ・設計書・共通スペックをまとめる。

理由:

- API とデータモデル（[`00-overview.md`](00-overview.md) §4）を `spec/` に **Single Source of Truth** として置き、サーバー実装と Android 実装の両方が同じ JSON Schema / 同じサンプルでテストできる。
- API を変更する PR で、サーバー側とアプリ側の対応を同時にレビューできる。
- 設計書と実装が同じ履歴で動く。

トレードオフ: Python（サーバー）と Gradle（Android）が同居するため、CI はパスで分割する。個人〜小規模開発ではこの構成が最も管理しやすい。

### ポリレポにする場合（代替案）

規模が大きくなり公開範囲を分けたくなったら、次の3つに分割する:

| リポジトリ | 内容 |
| --- | --- |
| `every-routes-spec` | `spec/`（OpenAPI・JSON Schema・examples・CHANGELOG）。他2つが依存 |
| `every-routes-server` | Python サーバー。spec を git submodule か生成パッケージで取り込む |
| `every-routes-android` | Android アプリ。spec から DTO を生成 or 取り込み |

以下はモノレポ前提で記述する。

## 2. トップレベル構成

```
every-routes/
├── README.md                  # プロジェクト概要、各ディレクトリへの入口
├── LICENSE
├── CONTRIBUTING.md            # 開発手順、ブランチ運用
├── .gitignore
├── .editorconfig
│
├── design/                    # 設計書（本ディレクトリ）
│   ├── 00-overview.md
│   ├── 01-every-routes-server.md
│   ├── 02-every-routes-android.md
│   └── 03-repository-structure.md
│
├── spec/                      # 端末・サーバー・外部アプリ共通の契約
│   ├── openapi.yaml           # /api/v1 の API 定義
│   ├── schemas/
│   │   ├── routine-profile.schema.json
│   │   ├── task.schema.json
│   │   └── error.schema.json
│   ├── examples/              # 正常系サンプル（両実装のテストで共用）
│   │   ├── routine-weekday.json
│   │   ├── routine-holiday.json
│   │   ├── task-agent.json
│   │   └── task-google.json
│   └── CHANGELOG.md           # schemaVersion / API バージョンの変更履歴
│
├── server/                    # Every routes server（Python）  → §3
├── android/                   # Every routes（Android / Kotlin）→ §4
│
├── tools/
│   ├── validate-spec.sh       # examples/ を schemas/ で検証
│   └── gen-openapi-docs.sh    # openapi.yaml から HTML ドキュメント生成
│
└── .github/
    └── workflows/
        ├── spec-validate.yml  # spec の検証（全 PR）
        ├── server-ci.yml      # server/ 変更時：lint + pytest
        └── android-ci.yml     # android/ 変更時：build + unit test
```

## 3. `server/` — Python サーバー

```
server/
├── pyproject.toml             # パッケージ定義、依存、entry point (every-routes-server)
├── README.md                  # 起動方法、設定、API 早見表
├── config.example.yaml        # 設定ファイル雛形（01 設計書 §2 準拠）
│
├── every_routes_server/
│   ├── __init__.py
│   ├── __main__.py            # python -m every_routes_server
│   ├── cli.py                 # 引数解析（--config / --data-dir / --port）
│   ├── app.py                 # ASGI アプリ生成（FastAPI インスタンス、ルーター登録）
│   ├── config.py              # YAML + 環境変数の読み込み・検証
│   ├── auth.py                # Bearer トークン検証（定数時間比較）
│   │
│   ├── api/
│   │   ├── __init__.py
│   │   ├── meta.py            # GET /health, GET /info
│   │   ├── routines.py        # GET/PUT/DELETE /routines[/{addr}]
│   │   └── tasks.py           # GET/POST/PATCH/DELETE /tasks[/{id}]
│   │
│   ├── domain/
│   │   ├── models.py          # Pydantic モデル（spec/schemas と対応）
│   │   ├── conflict.py        # last-write-wins 判定（00 設計書 §6.2）
│   │   └── hashing.py         # content_hash 生成
│   │
│   ├── storage/
│   │   ├── base.py            # ストレージインターフェース
│   │   ├── sqlite.py          # 既定実装（単一ファイル）
│   │   └── migrations/        # スキーママイグレーション
│   │
│   └── maintenance.py         # tombstone の定期物理削除
│
├── tests/
│   ├── conftest.py            # テスト用クライアント、一時 DB
│   ├── test_meta.py
│   ├── test_routines_api.py   # 新規=201 / 更新=200 / 競合=409 / force
│   ├── test_tasks_api.py
│   ├── test_conflict.py
│   └── test_spec_examples.py  # spec/examples を投入して往復検証
│
├── scripts/
│   ├── dump.py                # 全ルーティン・タスクを JSON で書き出し（バックアップ）
│   └── restore.py             # dump を読み込んで再投入
│
├── deploy/
│   ├── Dockerfile
│   ├── docker-compose.yaml
│   ├── every-routes.service   # systemd（Linux）
│   └── com.every-routes.server.plist  # launchd（macOS）
│
└── examples/                  # 外部アプリ・エージェント向けサンプル
    ├── curl.md               # 代表的な API 呼び出し
    └── agent_client.py       # OpenClaw 等が「明日のタスク」を書き込む最小例
```

- `spec/schemas/*.json` と `every_routes_server/domain/models.py` は対応を保つ。CI（`spec-validate.yml` + `test_spec_examples.py`）でズレを検出。
- ストレージは `storage/base.py` の IF 越しに使い、既定は SQLite。将来別実装を足せる。

## 4. `android/` — Android アプリ（マルチモジュール）

```
android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   └── libs.versions.toml     # 依存バージョン一元管理
├── build-logic/              # convention plugin（共通 Gradle 設定。任意）
├── README.md
│
├── app/                      # エントリポイント（Application, MainActivity, ナビ, DI ルート）
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/.../ …
│
├── core/
│   ├── model/                # RoutineProfile / RoutineBlock / Task 等（spec 対応の純 Kotlin）
│   ├── common/               # 時刻・タイムゾーンユーティリティ、Result 型、祝日判定
│   ├── database/             # Room（Entity, DAO, TypeConverter, DB 定義）
│   ├── datastore/            # 設定の永続化（server_config, sync_state。トークンは Keystore 暗号化）
│   └── testing/              # フェイク Repository / テストデータ
│
├── data/
│   ├── network/              # Retrofit サービス（/api/v1）、DTO、自己署名証明書の信頼設定
│   ├── routine/              # RoutineRepository（ローカル CRUD + サーバー同期）
│   ├── task/                 # TaskRepository
│   ├── sync/                 # 同期エンジン、WorkManager Worker、競合解決、dirty 管理
│   └── googletasks/          # Google Sign-In + Tasks 取得、マッピング
│
├── domain/                   # ユースケース
│   │                         #   BuildTodayViewUseCase（今日ビュー組み立て：00 §4.3）
│   │                         #   UploadRoutineUseCase（手動アップロード：02 §5.2）
│   │                         #   ImportRoutineUseCase（インポート：02 §5.3）
│   │                         #   SyncNowUseCase / CompleteTaskUseCase
│   └── …
│
├── feature/
│   ├── today/                # 今日ビュー（統合タイムライン）
│   ├── routines/             # プロファイル一覧 + エディタ（曜日・祝日・時間帯ブロック）
│   ├── tasks/                # タスク一覧・手動追加
│   └── settings/             # 接続設定 / アップロード / インポート / Google 連携 / 同期ログ
│
└── widget/                   # Jetpack Glance（小・大ウィジェット。BuildTodayViewUseCase を共用）
```

- 依存方向: `feature/* → domain → data → core`（`core/model` は全体が参照）。
- `core/model` は `spec/schemas` に対応。DTO（`data/network`）↔ ドメインモデルの変換層を `data` 側に置く。
- ウィジェットとアプリ本体で今日ビュー計算（`BuildTodayViewUseCase`）を共有し、表示のズレを防ぐ。

## 5. バージョニングとリリース

| 対象 | 方式 |
| --- | --- |
| API / データモデル | `spec/openapi.yaml` の `info.version` と各ペイロードの `schemaVersion`。破壊的変更は `/api/v2` を追加し当面併存。`spec/CHANGELOG.md` に記録 |
| サーバー | git タグ `server-vX.Y.Z`。`server-ci.yml` でテスト、タグで Docker イメージ発行（任意） |
| Android | git タグ `android-vX.Y.Z`。`versionCode` / `versionName` を `libs.versions.toml` 由来で管理 |

- ブランチ: `main` を安定線、機能は `feature/*` ブランチ → PR。
- PR で `spec/` を変更したら、`server/` と `android/` の対応も同 PR に含めることを `CONTRIBUTING.md` に明記。

## 6. CI（`.github/workflows/`）

| ワークフロー | トリガー | 内容 |
| --- | --- | --- |
| `spec-validate.yml` | 全 PR | `tools/validate-spec.sh`：`spec/examples` を `spec/schemas` で検証、`openapi.yaml` の lint |
| `server-ci.yml` | `server/**` or `spec/**` 変更 | ruff/black lint、`pytest`（`test_spec_examples.py` 含む） |
| `android-ci.yml` | `android/**` or `spec/**` 変更 | `./gradlew assembleDebug testDebugUnitTest lint` |

## 7. 初期セットアップ手順（README に記載する想定）

```bash
# リポジトリ取得
git clone <repo> every-routes && cd every-routes

# --- サーバー ---
cd server
python -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
cp config.example.yaml every-routes.yaml   # auth_tokens を書き換える
every-routes-server --config every-routes.yaml

# --- Android ---
cd ../android
./gradlew assembleDebug
# 端末の設定画面でサーバーの base URL とトークンを入力
```
