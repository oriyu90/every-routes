# Every Routes

1日の予定・生活ルーティンを曜日・祝日条件で自動適用する Android アプリ **「Every routes」** と、そのデータを複数端末や外部の AI エージェント（OpenClaw など）と安全に共有・同期するための軽量サーバー **「Every routes server」** のモノレポプロジェクトです。

## プロジェクト構成

```text
every-routes/
├── design/         # 設計書ドキュメント
├── spec/           # 共通 API 仕様（OpenAPI）および JSON スキーマ
├── server/         # Python FastAPI 共有サーバー実装
├── android/        # Android アプリ (Kotlin/Compose)
├── tools/          # validate-spec.sh / gen-openapi-docs.sh
├── .github/        # CI (spec/server/android)
└── every-routes.md # 開発・保守メモ
```

## 開発手順

```bash
# spec 検証
bash tools/validate-spec.sh

# サーバー
cd server && pip install -e ".[dev]" && python -m pytest

# Android
cd ../android && ./gradlew assembleDebug testDebugUnitTest lint
```

## リリース・紹介サイト

- 紹介サイト: https://studio-rizi.pages.dev/projects/every-routes/（日本語・English・中文・Português）
- サーバー `server-v0.1.0`: https://github.com/oriyu90/every-routes/releases/tag/server-v0.1.0（sdist + wheel）
- Android `android-v1.0.0`: https://github.com/oriyu90/every-routes/releases/tag/android-v1.0.0（署名済み APK、安定版）
- Android `android-v0.2.0`: https://github.com/oriyu90/every-routes/releases/tag/android-v0.2.0（署名済み APK、App-only WireGuard 対応）
- Android `android-v0.1.0`: https://github.com/oriyu90/every-routes/releases/tag/android-v0.1.0（署名済み APK）

## 著者 (Author)
`Yuki_Orita`

## ライセンス (License)
MIT License. 詳細は [LICENSE](LICENSE) をご参照ください。
