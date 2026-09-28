# 場邊教練 Android（原生版）

- **下載**：https://github.com/yuhyahliu/table_tennis/releases/latest/download/tt-coach.apk
- 每次推送 `android/` 的變更，GitHub Actions 會跑單元測試、編譯 APK，並更新上面的下載連結。

## 在 Pixel 上安裝
1. 用 Chrome 打開上面的下載連結，下載 `tt-coach.apk`。
2. 點下載完成的通知 → 安裝。第一次會要求允許 Chrome「安裝不明應用程式」，允許即可。
3. 之後更新：重新下載、安裝，會直接覆蓋舊版，設定和校正都會保留。

## 第一版（0.2）有什麼
- 相機每秒 60 張（手機支援時），手機上即時骨架辨識（MediaPipe，GPU）。
- 四點校正 + 放大鏡 + 手機擺放建議；比賽／練習兩種模式。
- 每分結束的提示；第 3 分後鎖定「本局重點」，之後每分回報 ✓/✗ 與連續做到幾分；目標依選手自己的水準調整。
- 大按鈕計分（長按收回）、局間重點、語音（中文）。
- **資料收集**：每次使用都存下骨架資料（`下載/TTCoach/*.jsonl.gz`）和影片（`影片/TTCoach/*.mp4`），用來調整擊球偵測、大臂小臂、轉胯。

## 程式
| 檔案 | 內容 |
| --- | --- |
| `Engine.kt` | 教練邏輯（校正、量測、回合判斷、提示、局間重點），純 Kotlin，有單元測試 |
| `CoachActivity.kt` | 相機、骨架辨識、計分、語音、資料記錄 |
| `OverlayView.kt` | 畫球桌框、校正點、放大鏡、骨架 |
| `MainActivity.kt` | 設定畫面 |
| `SessionLog.kt` | 骨架資料寫檔 |
| `EngineTest.kt` | 用參考比賽影片的骨架資料驗證引擎 |
