# Every Routes 保守メモと今後の予定

## 開発概要
Every Routes は、1日の予定・生活ルーティンを「ルーティンプロファイル」として登録・管理する Android アプリと、それらを共有するための共有サーバーです。

## リリース履歴
- `server-v0.1.0` (2026-09-11): FastAPI ハブ `/api/v1`（ルーティン・タスク、LWW 競合解決、tombstone、SQLite 単一ファイル）。sdist + wheel を Release に添付。
- `android-v0.1.0` (2026-09-11): 今日ビュー、曜日・祝日条件、256bit ルーティンアドレス生成、日英 strings。upload キー署名済み APK を Release に添付。
- `android-v0.2.0` (2026-09-11): App-only WireGuard VPN（既定OFF、DIRECT/VPN 切替、.conf インポート・手動入力・削除、3回再試行＋ログ、多段接続テスト）。設計 `design/04-app-wireguard-vpn.md`。server / spec 無変更。
- 紹介サイト: https://studio-rizi.pages.dev/projects/every-routes/（ja/en/zh/pt）

## 保守・保守責任者
- 開発・保守者: Yuki_Orita
- 共通ルールリポジトリ: `oriyu90/common-rules-document`
- 署名用キーストア: `common-rules-document/keystores/every-routes-upload-key.jks`（alias `upload`）。リリース時は `KEYSTORE_PATH` / `STORE_PASSWORD` / `KEY_PASSWORD` / `KEY_ALIAS` を指定して `./gradlew assembleRelease`。

## 今後のアップデート予定
1. プッシュ通知によるリマインド機能の追加
2. Google Tasks への書き戻し機能（双方向同期）のサポート
3. iOS / Wear OS 向けのクライアント拡張
4. ルーティンブロックからの個別アラーム通知設定
5. Room / Retrofit / WorkManager による本格同期エンジンと Glance ウィジェット（現状は足場実装）
6. VPN 複数プロファイル、QR インポート、VPN 内 DNS 診断（v0.2.0 では単一プロファイル・手動接続のみ）

## セキュリティ・運用上の注意点
- 本サーバーは信頼されたネットワーク（LAN や VPN 等）内での運用を前提としています。
- 認証トークンは設定ファイルや環境変数で管理し、リポジトリにコミットしないよう厳重に管理してください。
- APK の署名パスワード類や Cloudflare トークンは `common-rules-document` の該当ルール文書のみに置き、他所へ平文保存しないでください。
