# Contributing to Every Routes

## 開発手順
1. 本リポジトリは、仕様（`spec/`）、サーバー（`server/`）、Android アプリを1つの履歴で管理するモノレポ構成を採用しています。
2. API の仕様を変更する際は、必ず `spec/openapi.yaml` や対応する JSON Schema を更新し、サーバー側の Python 実装と同時に Pull Request を作成・レビューしてください。
3. 機能追加やバグ修正は `feature/*` などのトピックブランチを作成して開発を行ってください。
