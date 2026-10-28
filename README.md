# MicroGLogin

讓以 LSPatch 重新簽名的 app 能用 Google 登入的 Xposed 模組。搭配 LSPatch 的「GMS 替代品」選項，把 app 對 Google Play 服務的呼叫導向 MicroG RE（`app.revanced.android.gms`）等替代品，補上 LSPatch loader 沒做、但登入需要的部分。

以 libxposed API 102 撰寫，最低支援 Android 10。

## 為什麼需要

patch 後的 app 改用 patch 工具的金鑰簽名。Google Play 服務在自己的 process 裡比對呼叫端簽章，跟 Google API Console 登記的 SHA-1 對不上，登入就失敗（`UNREGISTERED_ON_API_CONSOLE`）。這個比對 app 內的 hook 碰不到，所以要分成兩邊處理：

| 部分 | 由誰負責 | 理由 |
|---|---|---|
| 在 manifest 寫入 `<vendor>.android.gms.SPOOFED_PACKAGE_SIGNATURE`（原始簽章），替代品依它向 Google 回報原始簽章 | [LSPatch](https://github.com/JingMatrix/LSPatch) 的「GMS 替代品」選項 | manifest 只能在 patch 時改 |
| package、component、provider authority 導向替代品；manifest 的 C2DM 改名 | LSPatch loader（同一個選項） | — |
| action 改名、框架出口補導向、chimera bind 拆解、`AccountManager` token、Credential Manager 互通 | 本模組 | LSPatch loader 不改 action，但 MicroG RE 的登入、identitycredentials、phenotype 等服務只宣告改名後的 action；只靠 loader 無法登入（智生活實測） |

用 root 的 LSPosed 時，app 沒有重新簽名，不需要本模組。

## 安裝

1. 安裝 MicroG RE，並在 MicroG RE 登入 Google 帳號。app 要 token 時會用 MicroG RE 裡的同名帳號，所以要登入同一個帳號。
2. 安裝 MicroGLogin APK。
3. 用 LSPatch patch 目標 app：「GMS 替代品」選 MicroG RE（`app.revanced.android.gms`），模組選 MicroGLogin。整合模式另外加入 `QUERY_ALL_PACKAGES` 權限，app 才看得到 MicroG RE。
4. 移除原版 app，再安裝 patch 版。簽章不同，無法直接覆蓋安裝。

推播的去向取決於 manifest 的 C2DM 名稱。用 LSPatch 的 GMS 替代品選項時，C2DM 已改名為 `<vendor>.android.c2dm.*`，推播交給替代品；模組從 app 要求的權限（`<vendor>.android.c2dm.permission.RECEIVE`）判斷這件事並推出替代品的 package。沒有改名時（例如只寫入簽章宣告的 patch），推播留在 Google Play 服務，導向目標預設為 MicroG RE。

## 行為

| 呼叫 | 去向 |
|---|---|
| `com.google.android.gms`、`com.google.android.gsf` 的 package、component、provider authority | 替代品（`<vendor>.android.gms`） |
| `com.google.android.gms.*` action | 改名為 `<vendor>.android.gms.*` |
| 推播：`com.google.android.c2dm.*`、`com.google.iid.*`、`com.google.android.gms.cloudmessaging.*` | C2DM 已改名：交給替代品，C2DM action 改為 `<vendor>.android.c2dm.*`（與 LSPatch loader 相同）。未改名：留在 Google Play 服務（FCM 不比對呼叫端簽章） |
| 新版 Play 服務函式庫的 chimera bind（`BoundService.START` + `chimera-action:<action>`，直接指定 GMS 的 component） | 拆回原本的 action 再導向替代品（替代品沒有這些 component）；留在 Google Play 服務的推播照原樣綁定 |
| `common.account.CHOOSE_ACCOUNT` | 系統帳號選擇器 |
| `AccountManager.getAuthToken`、`blockingGetAuthToken`，帳號類型 `com.google` | 改用 MicroG RE 的同名帳號（類型由 MicroG RE 的 authenticator 決定），否則 Google Play 服務會以 `UNREGISTERED_ON_API_CONSOLE` 拒絕 |
| `com.google.android.gms.chimera`、`com.google.android.gms.fonts` provider | 留在 Google Play 服務（MicroG 沒有 Dynamite 模組；字型與簽章無關，導過去會 authority 不符） |
| 指向 app 自己元件的 intent（例如 `SignInHubActivity`） | 不改 action |
| Android 14 以上的系統 Credential Manager | 對 app 隱藏，讓 androidx 改走 Play 服務實作，再導向 MicroG RE |
| Google ID 憑證類型 `com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL` | 送往替代品時改成它認得的 `<vendor>.android.libraries...`，回傳時改回；MicroG RE 回傳的 Bundle 轉成 Android 14 以上 androidx 讀的 `GetCredentialResponse`／`GetCredentialException` |

intent 的改寫在兩處進行：`Intent` 的建構子與 setter，以及框架送出前的 `ContextImpl.bindServiceCommon`、`startServiceCommon`、`Instrumentation.execStartActivity`。app 經 AOT 編譯後 setter 可能被 inline 而攔不到，送出前的改寫負責補上。

替代品的 package 不是 `<vendor>.android.gms` 形式時，視為沿用 Google 名稱的替代品，只改 package。替代品沒安裝，或 app 看不到它時，模組什麼都不做。

## 支援的 Google 登入方式

| app 使用的 API | 狀態 |
|---|---|
| play-services-auth（`GoogleSignInClient`） | 支援 |
| Identity API（`Identity.getSignInClient`） | 理論上支援，要看 MicroG RE 有沒有實作 |
| androidx Credential Manager + `googleid`（例如 LINE） | 支援（LINE 26.14.0 實測可取得 ID token）。副作用是 app 的 passkey 也改走 MicroG RE |
| 網頁 OAuth（Custom Tab） | 不經過 GMS，本來就能用 |

## 限制

- 推播留在 Google Play 服務的模式下，只裝 MicroG RE、沒有 Google Play 服務的裝置收不到推播。
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
