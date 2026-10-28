# MicroGLogin

讓以 LSPatch 重新簽名的 app 能用 Google 登入的 Xposed 模組。模組把 app 對 Google Play 服務的登入呼叫導向 MicroG RE（ReVanced GmsCore，`app.revanced.android.gms`），推播則留在 Google Play 服務的 FCM。

以 libxposed API 102 撰寫，最低支援 Android 10。

## 為什麼需要

patch 後的 app 改用 patch 工具的金鑰簽名。Google Play 服務在自己的 process 裡比對呼叫端簽章，跟 Google API Console 登記的 SHA-1 對不上，登入就失敗（`UNREGISTERED_ON_API_CONSOLE`）。這個比對 app 內的 hook 碰不到，所以要分成兩邊處理：

| 部分 | 由誰負責 | 理由 |
|---|---|---|
| 在 manifest 寫入 `app.revanced.android.gms.SPOOFED_PACKAGE_SIGNATURE`（原始簽章），MicroG RE 依它向 Google 回報原始簽章 | [kuomartin/LSPatch](https://github.com/kuomartin/LSPatch) 的「MicroG 支援」（`--microg`） | manifest 只能在 patch 時改 |
| 把 GMS 的 intent、bind 與 provider 呼叫改指向 MicroG RE | 本模組 | 在 app process 內 hook 就做得到 |

用 root 的 LSPosed 時，app 沒有重新簽名，不需要本模組。

## 安裝

1. 安裝 MicroG RE，並在 MicroG RE 登入 Google 帳號。Google Play 服務保留，推播要靠它。
2. 安裝 MicroGLogin APK。
3. 用 kuomartin/LSPatch patch 目標 app：開啟「MicroG 支援」，模組選 MicroGLogin。整合模式另外加入 `QUERY_ALL_PACKAGES` 權限，app 才看得到 MicroG RE。
4. 移除原版 app，再安裝 patch 版。簽章不同，無法直接覆蓋安裝。

## 行為

| 呼叫 | 去向 |
|---|---|
| `com.google.android.gms`、`com.google.android.gsf` 的 package、component、provider authority | MicroG RE |
| `com.google.android.gms.*` action | 改名為 `app.revanced.android.gms.*` |
| 推播：`com.google.android.c2dm.*`、`com.google.iid.*` | 留在 Google Play 服務（FCM 不比對呼叫端簽章） |
| `common.account.CHOOSE_ACCOUNT` | 系統帳號選擇器 |
| `com.google.android.gms.chimera` provider | 留在 Google Play 服務（MicroG 沒有 Dynamite 模組） |
| 指向 app 自己元件的 intent（例如 `SignInHubActivity`） | 不改 action |
| Android 14 以上的系統 Credential Manager（實驗） | 對 app 隱藏，讓 androidx 改走 Play 服務實作，再導向 MicroG RE |

MicroG RE 沒安裝，或 app 看不到它時，模組什麼都不做。

## 支援的 Google 登入方式

| app 使用的 API | 狀態 |
|---|---|
| play-services-auth（`GoogleSignInClient`） | 支援 |
| Identity API（`Identity.getSignInClient`） | 理論上支援，要看 MicroG RE 有沒有實作 |
| androidx Credential Manager + `googleid`（例如 LINE） | 實驗：靠隱藏系統 Credential Manager。副作用是 app 的 passkey 也改走 MicroG RE |
| 網頁 OAuth（Custom Tab） | 不經過 GMS，本來就能用 |

## 限制

- 只裝 MicroG RE、沒有 Google Play 服務的裝置收不到推播。
- Firebase API key 若設了 Android 應用程式限制，app 拿 FCM token 時會被擋（`API_KEY_ANDROID_APP_BLOCKED`）。在 LSPatch 開啟簽章繞過（level 1 以上）就能解決。
- NPatch 不會寫入 `SPOOFED_PACKAGE_SIGNATURE`，在 NPatch 下仍然無法登入。
- app 自己的簽章檢查與伺服器端完整性檢查，不在本模組範圍內。

## 建置

```bash
./gradlew assembleDebug
```

輸出位於 `app/build/outputs/apk/debug/app-debug.apk`。需要 JDK 與 Android SDK（compileSdk 37）。

## 除錯

```bash
adb logcat -s MicroGLogin
```

啟動時會記錄框架名稱與版本、MicroG RE 是否可見、各組 hook 是否安裝成功。之後每次改寫都會記一行 debug log。
