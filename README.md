# brain-flow-into-divoom

Divoom x Muse S の脳波連動 Android アプリ実装リポジトリです。

## 現在の実装状況

初期実装（フェーズ0〜フェーズ1の骨格）を開始しました。

- Android アプリ基盤を作成（Kotlin / Compose / minSdk 26 / targetSdk 35）
- モードA（オシロ）とモードB（VRChatロゴ）のフレーム生成ロジックを実装
- Divoom プロトコルの基本エンコーダを実装
- 送信レート制御用キュー（最新フレーム優先）を実装
- Bluetooth Classic SPP クライアントを実装（ペアリング済み Pixoo Backpack 接続）
- Compose 上で 16x16 プレビュー表示を実装
- UIから接続/切断/手動送信/自動送信の操作を実装
- ロジックとエンコーダの単体テストを追加

## プロジェクト構成

```text
app/
  src/main/java/io/rroki/brainflowintodivoom/
    data/
      bluetooth/BluetoothPermissionPolicy.kt
      divoom/DivoomBluetoothClient.kt
      divoom/DivoomConnectionState.kt
      divoom/DivoomPacketEncoder.kt
      divoom/FrameDispatchQueue.kt
      muse/MuseStreamGateway.kt
    domain/
      model/BrainBand.kt
      model/DisplayMode.kt
      processing/OscilloscopeFrameGenerator.kt
      processing/VrchatLogoFrameGenerator.kt
    presentation/
      MainScreen.kt
      MainUiState.kt
      MainViewModel.kt
    MainActivity.kt
  src/test/java/io/rroki/brainflowintodivoom/
    data/divoom/DivoomPacketEncoderTest.kt
    data/divoom/FrameDispatchQueueTest.kt
    domain/processing/OscilloscopeFrameGeneratorTest.kt
```

## 重要メモ

- Muse S 連携（BrainFlow）は現時点ではインターフェースのみです。実機接続は次フェーズで実装します。
- Divoom CRC 計算は現時点で検証用の暫定実装です。実機テストで最終調整します。
- `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`（Android 12+）と旧API向け権限分岐を実装済みです。
- Divoom接続は「ペアリング済みデバイス名 `Pixoo Backpack`」を前提にしています。

## 次フェーズ

1. BrainFlow SDK を組み込み、Muse S ストリーム取得を実装
2. 接続断時の再接続制御（指数バックオフ）を実装
3. モードA実機チューニング（正規化・FPS・視認性）
4. モードB（ロゴ同期カラー）の閾値チューニング

## ビルド環境セットアップ（Windows）

前提:

- Android Studio をインストール済み
- Android SDK が `C:/Users/<username>/AppData/Local/Android/Sdk` に存在

PowerShell 例:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat testDebugUnitTest --no-daemon
```

直近テスト結果:

- `testDebugUnitTest`: BUILD SUCCESSFUL

補足:

- SDK パスは `local.properties` の `sdk.dir` を利用
- 初回実行時に必要な Android SDK パッケージが自動インストールされる場合があります
