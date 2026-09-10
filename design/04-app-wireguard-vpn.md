# Every routes — アプリ内 WireGuard VPN（App-only）設計書

`00-overview.md` の「LAN / VPN 経由」接続を、Android アプリ内蔵の WireGuard トンネルに拡張する。
サーバー API・OpenAPI・JSON Schema・同期モデルは変更しない。VPN は通信経路の差し替えであり、Android 側だけで吸収する。

## 1. 接続モード

| モード | 動作 |
| --- | --- |
| `DIRECT`（既定） | 従来通り。LAN / 端末外の既存 VPN 経由でベース URL へ直接接続 |
| `APP_ONLY_WIREGUARD` | アプリ自身が WireGuard トンネルを開始し、その経路でサーバーへ接続 |

加えて `vpnEnabled` トグル（**既定 OFF**）を持ち、OFF の間は `APP_ONLY_WIREGUARD` 選択中であってもトンネルを開始しない。
プロファイル設定はトグルの ON/OFF と独立に保持・編集・削除できる（設定だけ残す運用が可能）。

## 2. App-only の強制ルール

- `VpnService.Builder.addAllowedApplication()` に自パッケージ `com.everyroutes.app` のみを指定する per-app VPN とする。
- インポートされた `.conf` の `IncludedApplications` / `ExcludedApplications` は信用せず、保存時に `IncludedApplications = com.everyroutes.app` へ強制上書きし、`ExcludedApplications` は空にする。
- 他アプリ・テザリング先端末の通信は VPN 対象外が設計目標（Android 16 実機で受け入れ試験する）。
- Android の VPN スロットは OS 仕様上 1 つ（同一ユーザー）。他 VPN 使用中は切り替わる旨を UI に明示する。Always-on VPN は要件にしない。

## 3. AllowedIPs の扱い

- Peer の `AllowedIPs` はインポート値を維持する（書き換えない）。
- `0.0.0.0/0` / `::/0` を含む設定は default route として検出し、UI に警告する（端末全体ではなく Every routes の全通信が VPN 経由になり得るため）。

## 4. VPN 状態

`DISABLED` / `CONFIG_MISSING` / `PERMISSION_REQUIRED` / `CONNECTING` / `CONNECTED` / `DEGRADED` / `DISCONNECTED` / `ERROR`

診断表示（秘密情報を除く）: 権限状態、プロファイル名、Endpoint ホスト、トンネル UP/DOWN、最新 handshake からの経過秒、RX/TX バイト、`/health` 到達性、認証付き API 成否、最終エラーコード、再試行回数。

PrivateKey / PresharedKey / Bearer トークンは表示・ログ・クラッシュ報告のいずれにも出さない。

## 5. 接続・再接続フロー

初回接続:

1. `.conf` をインポート（SAF）または手動入力 → 構文検証 → app-only サニタイズ → 暗号化保存
2. `VpnService.prepare()` → 未許可なら OS 許可ダイアログ（ユーザー操作が必須）
3. `GoBackend.setState(UP)` → handshake 確認 → `GET /api/v1/health` → 認証付き接続テスト

サーバー接続時の自動起動:

- モード VPN ＋ トグル ON ＋ 権限付与済み → トンネル DOWN なら自動 UP してから接続する。
- 権限未付与・設定なしの場合はバックグラウンドで許可 UI を出さず、同期は通常のオフライン失敗としてキューし、ログに理由を残す。

再接続:

- handshake タイムアウト・DNS 失敗・トンネル異常時は最大 3 回バックオフ再試行（約 2s / 5s / 10s）。
- それでも失敗したら `ERROR` で停止し、サニタイズ済みログを残す。**再開はユーザーの明示操作（再接続ボタン）のみ**。WorkManager から無理に再起動しない。

## 6. 接続テスト（多段診断）

`設定存在 → VPN 権限 → トンネル UP → handshake → 経路 → TCP 到達 → GET /health → 認証付き API` の順に診断し、失敗段のエラーコード（例: `VPN_PERMISSION_REQUIRED` / `WG_HANDSHAKE_TIMEOUT` / `SERVER_AUTH_FAILED`）を表示する。
VPN の問題・サーバー停止・トークン誤りを切り分けられること。

## 7. 保存と秘密情報

- WireGuard プロファイル（秘密鍵含む）・Bearer トークンは `EncryptedFile`（Android Keystore 律束の AES-GCM）でアプリ非公開領域に保存し、バックアップ対象外とする。
- `.conf` 読み込みは Storage Access Framework のみ（広いストレージ権限を要求しない）。
- ログは正規表現で `PrivateKey` / `PresharedKey` / トークンらしき値をマスクする。

## 8. モジュール構成（`com.everyroutes.app`）

```
vpn/
  VpnState.kt                 純粋: 状態 enum・診断データ・エラーコード
  WireGuardProfile.kt         純粋: プロファイル data class
  WireGuardSanitizer.kt       純粋(JVM可): .conf テキスト検証・app-only 固定・default route 検出
  VpnRetryPolicy.kt           純粋(JVM可): 再試行回数・バックオフ判定
  VpnLog.kt                   純粋(JVM可): リングバッファ・秘密情報マスク
  WireGuardProfileStore.kt    Android: EncryptedFile による暗号化保存・削除
  EveryRoutesVpnManager.kt    Android: GoBackend 制御・権限・自動起動・再試行・統計
  ServerReachability.kt       Android: /health + 認証付き到達性試験
settings/
  ConnectionSettings.kt       純粋+Android: モード・トグル（既定OFF）・URL の保持
```

純粋層は公式 tunnel ライブラリに依存せず JVM 単体テストで検証する。API・同期コードは VPN の有無を意識しない（OS ルーティングに委ねる）。

## 9. 受け入れ基準（抜粋。完全版は `02` §10）

- DIRECT 接続が従来通り動く。VPN 機能 OFF が既定で、OFF では何も変わらない。
- 別 `IncludedApplications` を含む設定でも自パッケージのみに固定される。
- `0.0.0.0/0` / `::/0` で警告が出る。秘密情報がログに出ない。
- 3 回再試行後に停止し、ログが残り、ユーザー操作なしで再開しない。
- VPN 拒否・切断でもオフライン機能（ルーティン・今日ビュー）は動く。
- 他 VPN 使用中の注意が表示される。targetSdk 36 で接続・切断・再接続が安定する。

## 10. 非目標（v0.2.0）

複数プロファイル保持、自動再接続の常駐化、QR インポート、Always-on VPN、独自 foreground service、VPN 内 DNS 診断の高度化。
