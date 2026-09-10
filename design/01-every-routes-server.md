# Every routes server — 設計書

共通概念・データモデル・同期ルールは [`00-overview.md`](00-overview.md) を参照。本書はサーバー固有の設計を扱う。

## 1. 目的と役割

- 複数の Android 端末、および OpenClaw などの外部アプリ／AI エージェントの間で、**ルーティンプロファイル・タスクを共有するためのハブ**。
- 単純なクラウド保存ではなく「端末Aが書いた情報を端末Bやエージェントが受け取る／エージェントが書いた情報を端末が受け取る」という **双方向・多アプリ共有** を成立させる。
- Mac / Linux で簡単に起動できる軽量サーバー。特定 OS に強く依存しない。

### やること

- 共有ルーティンプロファイル（内容・`routineAddress`・`lastModified`・繰り返し設定・時間帯ブロック）の保存と配布
- ルーティンのアップロード受け付け（新規追加 / 既存アドレスの更新の判定）
- ルーティン一覧の提供（他端末がインポート対象を選べるように）
- タスクの保存・取得・更新・論理削除（エージェントや端末が随時読み書き）
- `lastModified` に基づく競合検出

### やらないこと

- ユーザーアカウント／個人ごとの権限分離
- 通知・スケジューリングの実行（「明日の朝に取り込む」判断は端末側）
- Google Tasks との直接連携（端末が各自で行う）

## 2. 動作環境・配布

| 項目 | 方針 |
| --- | --- |
| 言語／実行 | Python（3.10+ 目安）。`pip install` 後にコマンド一発で起動、または `python -m every_routes_server` |
| フレームワーク | 軽量な ASGI（例：FastAPI + uvicorn）。依存は最小限 |
| データストア | 既定は単一ファイルの SQLite（外部 DB 不要）。データディレクトリを指定するだけで動く |
| OS | macOS / Linux。systemd / launchd / 手動起動いずれも可。Docker イメージも任意提供 |
| ポート | 既定 `8787`（変更可） |

### 起動例

```bash
pip install every-routes-server
every-routes-server --config ./every-routes.yaml
# または
python -m every_routes_server --data-dir ./data --port 8787
```

### 設定ファイル（YAML 例）

```yaml
bind: 0.0.0.0          # LAN/VPN のインターフェイスにバインド
port: 8787
data_dir: ./data       # SQLite とログの置き場所
timezone: Asia/Tokyo   # サーバー既定タイムゾーン
holiday_region: JP      # 祝日カレンダーの地域
auth_tokens:            # 共有シークレット（複数可）
  - "change-me-please"
tls:                    # 任意。ローカルなら省略可
  cert_file: null
  key_file: null
cors_allow_origins: []  # 外部アプリをブラウザから使う場合のみ
```

## 3. ネットワーク・セキュリティ

- **接続経路**: 家庭内 LAN からの直接接続、または VPN で同一ネットワークに入った状態からの接続を想定。インターネットへの常時公開は非推奨。
- **認証**: すべての API 呼び出しに `Authorization: Bearer <token>` を要求。`auth_tokens` のいずれかに一致すれば許可（定数時間比較）。端末・エージェントで別トークンを配ってもよい。
- **TLS**: 任意。VPN 前提なら平文 HTTP でも可。TLS を使う場合は自己署名証明書を許容し、端末側で固定信頼できるようにする。
- **公開範囲**: `bind` を LAN / VPN インターフェイスに限定。ポートをインターネットへ転送しない運用を前提にドキュメント化。
- **レート制限 / ボディサイズ上限**: 誤用・事故対策として簡易なものを備える（例：1MB/リクエスト、毎分 N リクエスト）。
- **保存データ**: ユーザーが登録したルーティン名・予定タイトル等のみ。認証情報以外の秘匿データは持たない。

## 4. API

- ベースパス: `/api/v1`
- 形式: JSON（`Content-Type: application/json`）
- 時刻: すべて RFC3339。`lastModified` は UTC。
- エラー: `{ "error": { "code": "...", "message": "..." } }` と対応する HTTP ステータス。

### 4.1 メタ

| メソッド / パス | 説明 |
| --- | --- |
| `GET /api/v1/health` | 稼働確認。`{ "status": "ok" }` |
| `GET /api/v1/info` | サーバー情報。`{ "timezone": "...", "holidayRegion": "JP", "schemaVersions": { "routine": 1, "task": 1 }, "serverTime": "..." }` |

### 4.2 ルーティン

#### `GET /api/v1/routines`

共有中のルーティン一覧（インポート候補選択用）。本文は軽量メタのみ。

```json
{
  "routines": [
    { "routineAddress": "rt_9f2c...", "name": "平日", "lastModified": "2026-09-10T08:12:33Z", "schemaVersion": 1, "deleted": false },
    { "routineAddress": "rt_1a77...", "name": "休日", "lastModified": "2026-09-08T22:03:10Z", "schemaVersion": 1, "deleted": false }
  ]
}
```

クエリ: `?since=<RFC3339>`（その時刻以降に更新されたものだけ）、`?includeDeleted=true`。

#### `GET /api/v1/routines/{routineAddress}`

指定アドレスのルーティン全内容（[`00-overview.md`](00-overview.md) §4.1 の形式）。存在しなければ `404`。

#### `PUT /api/v1/routines/{routineAddress}`

アップロード（新規追加 / 既存更新の共通口）。本文はルーティン全内容。`routineAddress` はパスと本文で一致必須。

判定ロジック:

| 状況 | 挙動 | ステータス |
| --- | --- | --- |
| そのアドレスが未登録 | 新規レコードとして追加 | `201 Created` |
| 登録済みで、本文の `lastModified` がサーバー保持より新しい | 同じレコードを更新（**新規に増やさない**） | `200 OK` |
| 登録済みで、本文の `lastModified` がサーバー保持と同一かつ内容一致 | 変更なし | `200 OK`（no-op） |
| 登録済みで、本文の `lastModified` がサーバー保持より古い／内容衝突 | 拒否し、サーバー現行データを本文で返す | `409 Conflict` |

- クエリ `?force=true` を付けると `409` 条件でも上書きする（端末が明示的に選んだ場合のみ使用）。
- 応答本文には保存後の正データ（サーバーが確定した `lastModified` を含む）を返す。

#### `DELETE /api/v1/routines/{routineAddress}`

論理削除（tombstone 化）。物理削除はしない。他端末は次回同期で「削除された」と検知できる。

### 4.3 タスク

#### `GET /api/v1/tasks`

クエリ:

| パラメータ | 説明 |
| --- | --- |
| `from`, `to` | `at` の日付範囲（`YYYY-MM-DD`）。省略時はサーバー既定（例：今日〜+14日） |
| `since` | この時刻以降に `lastModified` が更新されたものだけ（差分同期用） |
| `source` | `google_tasks` / `agent` / `manual` で絞り込み |
| `includeDeleted` | tombstone を含めるか（差分反映のため既定 `true` を推奨） |

```json
{ "tasks": [ { "id": "tsk_3a91...", "source": "agent", "title": "資料作成", "at": "2026-09-11T15:00:00+09:00", "allDay": false, "status": "needsAction", "lastModified": "2026-09-10T09:00:00Z", "deleted": false } ] }
```

#### `POST /api/v1/tasks`

タスク追加（主に OpenClaw などのエージェント向け）。`id` を省略した場合はサーバーが採番して返す。`externalId` が既存タスクと一致する場合は更新として扱う（重複防止）。

例（エージェントが「明日これをやる」を書き込む）:

```http
POST /api/v1/tasks
Authorization: Bearer <token>

{ "source": "agent", "title": "銀行に行く", "at": "2026-09-11T10:00:00+09:00", "allDay": false }
```

#### `PATCH /api/v1/tasks/{id}`

部分更新（`status` 完了化、時刻変更など）。`lastModified` はサーバーが更新。旧 `lastModified` を投げた場合の競合は §4.2 と同じ方針（古ければ `409`）。

#### `DELETE /api/v1/tasks/{id}`

論理削除（`deleted: true`）。

## 5. データ設計（SQLite 既定）

```
routines
  routine_address   TEXT PRIMARY KEY
  name              TEXT
  last_modified     TEXT   -- UTC RFC3339
  schema_version    INTEGER
  payload           TEXT   -- ルーティン全内容(JSON)。blocks / recurrence を含む
  content_hash      TEXT   -- 競合検出用
  deleted           INTEGER DEFAULT 0
  created_at        TEXT
  updated_at        TEXT

tasks
  id                TEXT PRIMARY KEY
  source            TEXT
  external_id       TEXT   -- (source, external_id) にユニークインデックス
  title             TEXT
  at                TEXT   -- タイムゾーン付き RFC3339
  all_day           INTEGER DEFAULT 0
  status            TEXT   -- needsAction / completed
  last_modified     TEXT   -- UTC
  deleted           INTEGER DEFAULT 0
  payload           TEXT   -- 拡張フィールド保持用(JSON)
  created_at        TEXT
  updated_at        TEXT

routine_history (任意 / 監査用)
  routine_address   TEXT
  last_modified     TEXT
  payload           TEXT
  replaced_at       TEXT
```

- `payload` に全文を持たせることで、スキーマ拡張時もサーバー改修なしで受け渡しできる（サーバーは中継が主目的）。
- インデックス: `routines(last_modified)`, `tasks(at)`, `tasks(last_modified)`, `tasks(source, external_id)`。
- tombstone は一定期間（例：90日）後にバックグラウンドで物理削除してよい（設定可能）。

## 6. 競合解決（サーバー側の責務）

[`00-overview.md`](00-overview.md) §6.2 に準拠。サーバーは:

1. `PUT` / `PATCH` 受信時に、保持中の `last_modified` と本文の `last_modified` を比較。
2. 本文が新しい → 保存し、`routine_history` に旧版を退避（任意）。
3. 本文が古い or 同時刻で内容不一致 → `409` とともに現行データを返す（`?force=true` で上書き）。
4. 保存後は必ずサーバー時刻で `last_modified` / `updated_at` を確定し、応答に含める。

## 7. 外部アプリ／エージェント連携（OpenClaw 等）

- Android 専用にしない。同じ `/api/v1` を、同じ Bearer トークンで叩ければ誰でも利用可能。
- 典型フロー:
  1. 日中、ユーザーが OpenClaw に「明日はこれをやる」と伝える
  2. OpenClaw が `POST /api/v1/tasks`（`source: "agent"`, `at` に翌日時刻）でサーバーへ書き込む
  3. 翌朝など決めたタイミングで Android アプリが `GET /api/v1/tasks?from=...&since=...` を実行
  4. エージェントが追加したタスクが端末の「今日ビュー」に取り込まれる
- エージェントがルーティン自体を編集することも `PUT /api/v1/routines/{addr}` で可能（同じ競合ルール）。
- 連携先向けに、API リファレンス（本章＋§4）と最小サンプル（curl / Python）を同梱する。

## 8. 運用

- **ログ**: リクエスト概要（メソッド・パス・ステータス・所要時間）、競合発生、tombstone 掃除。トークン値はログに残さない。
- **バックアップ**: `data_dir` の SQLite ファイルをコピーするだけ。エクスポート用に `GET /api/v1/routines`（全件）+ 各詳細、`GET /api/v1/tasks` を使ったダンプスクリプトを同梱。
- **バージョニング**: URL に `/v1`。本文に `schemaVersion`。破壊的変更時は `/v2` を追加し当面併存。
- **監視**: `GET /api/v1/health` を死活監視に使用。

## 9. 受け入れ基準（サーバー）

- [ ] LAN 上の別マシンから Bearer トークン付きで `GET /api/v1/health` が 200 を返す
- [ ] 新規 `routineAddress` を `PUT` すると 201、同アドレスを新しい `lastModified` で再 `PUT` すると 200 で件数が増えない
- [ ] 古い `lastModified` の `PUT` が 409 を返し、現行データを本文で返す。`?force=true` で上書きできる
- [ ] `GET /api/v1/routines` が一覧メタを返し、`since` で差分が取れる
- [ ] `POST /api/v1/tasks` でエージェントがタスクを追加でき、`GET /api/v1/tasks?from&to` で別クライアントが取得できる
- [ ] `DELETE` が物理削除でなく tombstone になり、`includeDeleted=true` で検知できる
- [ ] サーバー再起動後もデータが保持される（SQLite ファイル）
