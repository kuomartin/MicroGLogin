# MicroGLogin 實作計畫

目標：libxposed API 102 模組。搭配 LSPatch「GMS 替代品」選項（JingMatrix/LSPatch#127），讓 patch 過的任意 app 能經 MicroG RE（`app.revanced.android.gms`）等替代品登入 Google。app 用哪一種登入 API，就照那一種導向，不強制改走其他 API。manifest 的簽章宣告與 C2DM 改名由 LSPatch 在 patch 時寫入，不在本模組範圍。測試機為 Pixel 7a（Android 17）。

## 分工

| 部分 | 位置 | 理由 |
|---|---|---|
| `<vendor>.android.gms.SPOOFED_PACKAGE_SIGNATURE`、C2DM 改名 | LSPatch「GMS 替代品」選項 | manifest 只能在 patch 時寫入 |
| package、component、provider authority 導向；C2DM action 改寫；`isGooglePlayServicesAvailable` | LSPatch loader | 上游已實作（`bbd4be9`） |
| 整合模式的 `QUERY_ALL_PACKAGES` | 使用者在 LSPatch「新增權限」自行加入 | app 看不到替代品時，導向無法啟動 |
| action 改名、框架出口、chimera 拆解、帳號 token、Credential Manager 互通 | 本模組 | 上游 loader 沒有；MicroG RE 只宣告改名後的 action，只靠 loader 無法登入（智生活實測） |

## 實作項目

| # | 項目 | 做法 | 理由 | 狀態 |
|---|---|---|---|---|
| 1 | Xposed 模組骨架 | `compileOnly` libxposed API 102；`META-INF/xposed/`：`java_init.list`、`module.prop`（`staticScope=false`），不放 `scope.list`；無設定頁 | 通用模組，作用域由使用者在 LSPatch 為各 app 勾選 | 完成 |
| 2 | `minSdk = 29` | — | `onPackageLoaded` 標註 `@RequiresApi(Q)`，導向必須在這個時間點啟動 | 完成 |
| 3 | 規則集中在 `GmsRules` | 純字串函式：package、authority、action 改寫與還原（action 只在替代品宣告了改名後的名稱時才改，由 hook 端注入的查詢決定）；推播判定；`CHOOSE_ACCOUNT`；self-targeted；chimera action 解析；Google ID 憑證類型互換 | 不依賴 Android 執行環境，可用 JVM 單元測試覆蓋 | 完成，18 個測試 |
| 4 | 啟動時機與條件 | `onPackageLoaded`（只處理 `isFirstPackage`）裝框架 hook；向 `IPackageManager` 查 app 要求的權限，有 `<vendor>.android.c2dm.permission.RECEIVE` 就以 `<vendor>.android.gms` 為目標、推播交給它，否則目標為 MicroG RE、推播留 GMS；目標看不到就不裝。需要 app class 的 hook 放在 `onPackageReady` | `AppComponentFactory` 建立前就要攔到 GMS intent；這時沒有 `Context`。LSPatch 在 app process 內把 patch 版 apk 的讀取轉到原始 apk，讀不到 `config.json`，系統的權限紀錄不受影響 | 完成，智生活實測 |
| 5 | `Intent` 建構子與 setter 的導向 | `setPackage`、`setAction`、`setComponent`、`setClass`、`setClassName`、`readFromParcel`、所有建構子；`ContextWrapper.startActivity`、`Activity.startActivityForResult`；`ContentResolver` 的 `acquire*`（略過 abstract）與 `call`。例外直接寫 `mAction`、`mPackage`、`mComponent` 欄位 | 移植自 LSPatch `74a7883` 的 `GmsRedirector`；直接寫欄位避免再次觸發 hook | 完成 |
| 6 | 框架出口的導向 | `ContextImpl.bindServiceCommon`、`startServiceCommon`、`Instrumentation.execStartActivity(ies)`，送出前完整改寫一次 | LINE 的 GMS bind 沒經過 #5 的 hook（可能是 AOT inline，未證實），出口一定會經過 | 完成，LINE 實測 |
| 7 | chimera bind 拆解 | `BoundService.START` + `chimera-action:<action>` 且 component 屬於 GMS（或已被改成 MicroG RE）時，拆回原本的 action 再導向；推播則讓 component 留在 GMS | 新版 Play 服務函式庫直接綁定 GMS 的 chimera component，MicroG RE 沒有這些 component | 完成，LINE 實測 |
| 8 | 例外 | 指向 app 自己的 intent 還原 action（`SignInHubActivity` 只認 `GOOGLE_SIGN_IN`）；`CHOOSE_ACCOUNT` 交系統帳號選擇器；C2DM 未改名時推播（`c2dm`、`iid`、`cloudmessaging`）留 GMS，已改名時 C2DM action 改為 `<vendor>.android.c2dm.*`；`chimera`、`fonts` provider 不導向；不是 `<vendor>.android.gms` 形式的替代品只改 package | `fonts` 導過去會 authority 不符；推播方向必須與 manifest 的 C2DM 名稱一致 | 完成 |
| 9 | `AccountManager` token | `getAuthToken` 所有多載與 `blockingGetAuthToken`：帳號類型 `com.google` 換成替代品 authenticator 的類型（MicroG RE 是 `app.revanced`），名稱不變 | 登入後 app 以 `com.google` 帳號要 token，系統交給 Google Play 服務，回 `UNREGISTERED_ON_API_CONSOLE` | 完成，智生活實測 |
| 10 | Android 14 以上的 Credential Manager | `ContextImpl.getSystemService("credential")` 回傳 `null`，androidx 改走 Play 服務實作，再由 #6、#7 導向 | 系統路徑經 system_server 交給 provider，模組攔不到；MicroG RE 的 `GoogleIdService` 是系統 provider，一般 app 安裝時不會被系統綁定 | 完成，LINE 實測 |
| 11 | Google ID 憑證互通 | 送出：`com.google.android.gms.identitycredentials.GetCredentialRequest` 建好後走訪欄位，把 `com.google...TYPE_GOOGLE_ID_TOKEN_CREDENTIAL` 換成 `<vendor>.android.libraries...`。回傳：`ResultReceiver.send` 換回原名，並把 MicroG RE 的 Bundle 結果轉成 `GetCredentialResponse`／`GetCredentialException`，頭像字串轉 `Uri` | MicroG RE 的設計前提是 ReVanced patch 過的 app（字串已改名），且 Play 服務實作在 Android 13 以下才讀 Bundle；#10 讓 Android 14 以上也走這條路 | 完成，LINE 實測取得 ID token |
| 12 | 日誌 | tag `MicroGLogin`；啟動時記錄框架、各組 hook 結果；每次改寫記一行 debug | — | 完成 |
| 13 | README | 分工、安裝、行為對照、支援的登入方式、限制 | — | 完成 |

## 實機驗證結果

| app | 登入 API | MicroG RE 畫面 | 結果 |
|---|---|---|---|
| 智生活 4.20.2（LSPatch GMS 替代品 + 模組） | play-services-auth（`SignInHubActivity`） | 帳號對話框（`AuthSignInActivity`） | 登入成功；只靠 LSPatch loader 時無法登入 |
| LINE 26.14.0 | androidx Credential Manager + `GetSignInWithGoogleOption` | 底部面板（`AssistedSignInActivity`） | 取得 ID token，LINE 伺服器回「帳號未連結」（測試帳號本來就沒連結） |

## 未驗證的前提與待辦

| 項目 | 影響 | 驗證方式 |
|---|---|---|
| C2DM 改名模式下推播經 MicroG RE 送達 | 智生活目前沿用快取的 FCM token，尚未重新註冊 | 清除智生活資料重新註冊，或等真實推播 |
| C2DM 未改名模式下 FCM register3 不比對簽章 | 不成立的話推播留 GMS 沒有意義 | LINE（尚未用 GMS 替代品重新 patch）測推播 |
| #11 的入口 class `GetCredentialRequest` 在其他 app 可能被 R8 改名 | 該 app 的憑證類型不會轉換，新版登入失敗 | 找第二個用 Credential Manager 的 app 測試；必要時改在框架層攔截 `Parcel` 寫入 |
| #11 綁定 MicroG RE 7.2.1 的憑證類型命名 | MicroG RE 改名後要跟著改 | MicroG RE 更新時重測 LINE |
| #10 讓 app 的 passkey 也改走 MicroG RE | passkey 能否使用取決於 MicroG RE 的 FIDO 實作 | 找有 passkey 的 app 測試 |
| Identity API（`Identity.getSignInClient`）直接呼叫的 app | 理論上由 #5–#7 涵蓋 | 找使用該 API 的 app 測試 |
| DumbDaily README 仍寫舊的 fork 選項 | 文件與實際分工不符 | 改為「LSPatch GMS 替代品 + MicroGLogin」，並移除「LSPatch 轉送 token」的描述 |
| 上游 loader 只靠自己無法讓 MicroG RE 登入 | 不裝模組的使用者登不進去 | 是否回報 JingMatrix/LSPatch 待使用者決定 |
