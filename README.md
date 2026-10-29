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
| `com.google.android.gms.*` action | 替代品宣告了改名後的 `<vendor>.android.gms.*` 就改名，否則保留 Google 原名（MicroG RE 7.2.1 大多只有改名後的名稱，少數如 `ads.identifier.service.START` 只有原名）；向 PackageManager 查詢，每個 action 快取一次 |
| 推播：`com.google.android.c2dm.*`、`com.google.iid.*`、`com.google.android.gms.cloudmessaging.*` | C2DM 已改名：交給替代品，C2DM action 改為 `<vendor>.android.c2dm.*`（與 LSPatch loader 相同）。未改名：留在 Google Play 服務（FCM 不比對呼叫端簽章） |
| 新版 Play 服務函式庫的 chimera bind（`BoundService.START` + `chimera-action:<action>`，直接指定 GMS 的 component） | 拆回原本的 action 再導向替代品（替代品沒有這些 component）；留在 Google Play 服務的推播照原樣綁定 |
| `common.account.CHOOSE_ACCOUNT` | 系統帳號選擇器，只列替代品的帳號（`allowableAccountTypes` 等 extra 的 `com.google` 換成替代品的帳號類型）；選擇結果的帳號類型改回 `com.google`。理由：Google Play 服務的帳號在重新簽名的 app 拿不到 token，替代品沒有同名帳號就失敗 |
| `AccountManager.getAuthToken`、`blockingGetAuthToken`，帳號類型 `com.google` | 改用 MicroG RE 的同名帳號（類型由 MicroG RE 的 authenticator 決定），否則 Google Play 服務會以 `UNREGISTERED_ON_API_CONSOLE` 拒絕 |
| `com.google.android.gms.chimera`、`com.google.android.gms.fonts` provider | 留在 Google Play 服務（MicroG 沒有 Dynamite 模組；字型與簽章無關，導過去會 authority 不符） |
| 指向 app 自己元件的 intent（例如 `SignInHubActivity`） | 不改 action |
| Android 14 以上，Credential Manager 含 Google ID 選項的請求（`CredentialManager.getCredential`） | 模組在 app 內代接：以呼叫端 Activity 開啟替代品的 `ASSISTED_SIGNIN`（帶 SafeParcel 編碼的 `GoogleSignInOptions`、`GetSignInIntentRequest`），替代品顯示帳號對話框；結果在 `Activity.dispatchActivityResult` 收回，組成 `GetCredentialResponse`。同一請求裡的 passkey、密碼選項捨棄；不含 Google ID 的請求照常交給系統。理由：系統只把 Google ID 請求交給裝在 `/system` 的系統 provider，一般安裝的 MicroG RE 接不到（microg/GmsCore#3694） |
| Android 13 以下，Google ID 憑證類型 `com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL`（androidx 走 Play 服務的 identitycredentials） | 送往替代品時改成它認得的 `<vendor>.android.libraries...`，回傳時改回；MicroG RE 回傳的 Bundle 轉成 Android 14 以上 androidx 讀的 `GetCredentialResponse`／`GetCredentialException` |

intent 的改寫在兩處進行：`Intent` 的建構子與 setter，以及框架送出前的 `ContextImpl.bindServiceCommon`、`startServiceCommon`、`Instrumentation.execStartActivity`。app 經 AOT 編譯後 setter 可能被 inline 而攔不到，送出前的改寫負責補上。

替代品的 package 不是 `<vendor>.android.gms` 形式時，視為沿用 Google 名稱的替代品，只改 package。替代品沒安裝，或 app 看不到它時，模組什麼都不做。

## 支援的 Google 登入方式

| app 使用的 API | 狀態 |
|---|---|
| play-services-auth（`GoogleSignInClient`） | 支援 |
| Identity API（`Identity.getSignInClient`） | 理論上支援，要看 MicroG RE 有沒有實作 |
| androidx Credential Manager + `googleid`（例如 LINE、Speedtest） | Android 14 以上由模組代接，顯示替代品的帳號對話框（LINE 26.14.0 實測取得 ID token；Speedtest 實測開啟對話框）。`GetGoogleIdOption` 的 `filterByAuthorizedAccounts`、`autoSelectEnabled` 不支援，一律顯示對話框。Android 13 以下未實測 |
| 網頁 OAuth（Custom Tab） | 不經過 GMS，本來就能用 |

## 限制

- 推播留在 Google Play 服務的模式下，只裝 MicroG RE、沒有 Google Play 服務的裝置收不到推播。
- Firebase API key 若設了 Android 應用程式限制，app 拿 FCM token 時會被擋（`API_KEY_ANDROID_APP_BLOCKED`）。在 LSPatch 開啟簽章繞過（level 1 以上）就能解決。
- NPatch 不會寫入 `SPOOFED_PACKAGE_SIGNATURE`，在 NPatch 下仍然無法登入。
- LSPatch 的「注入 dex」選項會讓本模組載入失敗（`NoSuchMethodError: kotlin.Pair.component1()`）：注入後 loader 拿到的是 app 裡被 R8 刪減過的 Kotlin 標準函式庫。LINE 實測如此，patch 時關閉注入 dex 即可。
- MicroG RE 7.2.2 要求同意 OAuth 權限時（例如 LINE 的 Google 雲端硬碟備份），`UnpackingRedirectActivity` 在開出同意對話框後閃退（主題不是 AppCompat），按允許或拒絕都會再開一次，token 回不到 app。在 MicroG RE 的設定中先核准該 app 即可避開。
- app 自己的簽章檢查與伺服器端完整性檢查，不在本模組範圍內。

## 建置

```bash
./gradlew assembleDebug
```

輸出位於 `app/build/outputs/apk/debug/app-debug.apk`。需要 JDK 與 Android SDK（compileSdk 37）。

## 除錯

```bash
adb logcat -c
```

```bash
adb logcat -s MicroGLogin
```

先清除再開始記錄，然後才重現問題。logcat 的 main buffer 預設只有 256 KiB，系統 log 多的裝置上只留得住最後幾分鐘，app 啟動時的記錄很快就被擠掉。

啟動時會記錄框架名稱與版本、MicroG RE 是否可見、各組 hook 是否安裝成功。之後每次改寫都會記一行 debug log。
