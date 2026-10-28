# MicroGLogin 實作計畫

目標：libxposed API 102 模組。讓以 kuomartin/LSPatch「MicroG 支援」（`--microg`）patch 過的任意 app，把 Google 登入導向 MicroG RE（`app.revanced.android.gms`），推播留在 Google Play 服務的 FCM。manifest 簽章宣告（`SPOOFED_PACKAGE_SIGNATURE`、`fake-signature`）由 LSPatch 在 patch 時寫入，不在本模組範圍。測試機為 Pixel 7a（Android 17）。

## 分工

| 部分 | 位置 | 理由 |
|---|---|---|
| `SPOOFED_PACKAGE_SIGNATURE`、`fake-signature`、`FAKE_PACKAGE_SIGNATURE` | LSPatch `feat/microg-unified` @ `de23a35` | manifest 只能在 patch 時寫入 |
| 整合模式的 `QUERY_ALL_PACKAGES` | 使用者在 LSPatch「新增權限」自行加入 | app 看不到 MicroG RE 時，導向無法啟動 |
| GMS 導向、推播分流、Credential Manager fallback | 本模組 | 都是在 app process 內 hook 就做得到；上游 loader 的判準是「模組做得到的事不放進 loader」（PR 945585d） |

## 實作項目

| # | 項目 | 做法 | 理由 |
|---|---|---|---|
| 1 | 專案改成 Xposed 模組 | 移除 appcompat、material、espresso 與主題資源；`compileOnly` libxposed API 102；`META-INF/xposed/`：`java_init.list`、`module.prop`（`minApiVersion=102`、`targetApiVersion=102`、`staticScope=false`），不放 `scope.list`；keep rules 照 DumbDaily | 通用模組，作用域由使用者在 LSPatch 為各 app 勾選；不需要設定頁 |
| 2 | `minSdk = 29` | — | `onPackageLoaded` 標註 `@RequiresApi(Q)`，導向必須在它這個時間點啟動（見 #4）。LSPatch 支援 28，但 Android 9 已無實際使用者，不值得為它另寫 `onPackageReady` 的退路 |
| 3 | 規則抽成純函式 `GmsRules` | `redirectPackage`、`redirectAuthority`（`chimera` 不動）、`redirectAction`、`originalAction`、`isPushAction`（`com.google.android.c2dm.`、`com.google.iid.`）、`isChooseAccountAction`、`isSelfTargeted`；建構時傳入目標 package 與 app 自己的 package；不依賴 Android 執行環境 | 可以用 JVM 單元測試覆蓋；原 `GmsRedirector` 用 static 可變狀態，改成一個 instance |
| 4 | 啟動時機與條件 | `onPackageLoaded`，只處理 `isFirstPackage`。用 `ActivityThread.getPackageManager()`（`IPackageManager`）查 MicroG RE 是否可見，查不到就記 log 提示加 `QUERY_ALL_PACKAGES`，然後不裝 hook | 這時 `AppComponentFactory` 還沒建立，app 在 `<clinit>` 建立的 GMS intent 也攔得到；這時還沒有 `Context`，只能用 hidden API 的 `IPackageManager`，它的可見性過濾跟 app 自己查詢時一樣 |
| 5 | 移植 hook 到 API 102 | `Intent` 所有建構子；`setPackage`、`setAction`、`setComponent`、`setClass`、`setClassName`、`readFromParcel`；`ContextWrapper.startActivity`、`Activity.startActivityForResult`；`ContentResolver.acquire*`、`call`。逐一列出 `Intent.class.declaredMethods` 中同名的方法並 `hook(m).intercept`；改參數用 `chain.proceed(args)`，after 邏輯放在 `proceed()` 之後；欄位 `mAction`、`mPackage`、`mComponent` 用反射直接寫 | API 102 沒有 `hookAllMethods`；直接寫欄位，避免再次觸發 hook 而重新導向 |
| 6 | 保留原有的例外 | 指向 app 自己的 intent 還原 action（`SignInHubActivity` 只認 `GOOGLE_SIGN_IN`）；`CHOOSE_ACCOUNT` 交給系統帳號選擇器 `android/.accounts.ChooseTypeAndAccountActivity`；推播 intent 的 package／component 寫回 `com.google.android.gms` | 行為和 `74a7883` 的 `GmsRedirector` 一致 |
| 7 | 和 NPatch 自帶導向並存 | 不特別處理 | 規則是冪等的：package 已經是 MicroG RE 就不會再改，action 已經是 `app.revanced.android.gms.*` 也不會再改，self-targeted 還原照常生效。NPatch 不寫 `SPOOFED_PACKAGE_SIGNATURE`，登入仍會失敗，README 會註明 |
| 8 | Android 14 以上的 Credential Manager fallback（實驗） | 寫死開啟，驗證後再決定要不要做成設定。Hook `android.app.ContextImpl.getSystemService(String)`，`"credential"` 回傳 `null`；`getSystemService(Class)` 最後也會走到這裡 | 讓 androidx 退回 `CredentialProviderPlayServicesImpl`，再由 #5 導向 MicroG RE。副作用：app 的 passkey 也改走 MicroG |
| 9 | 日誌 | 沿用 DumbDaily 的 `Log` 包裝，tag 為 `MicroGLogin`；啟動時記錄框架名稱與版本、MicroG RE 是否可見、各組 hook 是否裝成功；每次改寫記一行 debug | — |
| 10 | 單元測試 | 針對 #3 的 JUnit 測試：gms／gsf 改寫、chimera 不動、c2dm／iid 判定、self-targeted 還原、CHOOSE_ACCOUNT 例外、已導向的值不會再改 | 規則是這個模組最容易出錯的地方，也是唯一不需要實機就能驗證的部分 |
| 11 | README | 安裝步驟（LSPatch `--microg` + MicroG RE + 勾選本模組）、支援的登入 API 對照表、限制（只裝 MicroG RE 沒有 Play 服務時收不到推播、NPatch 不支援、Firebase API key 有簽章限制時要開簽章繞過） | — |
| 12 | 實機驗證 | 智生活：Google 登入成功；`adb logcat -s MicroGLogin` 出現推播保留給 GMS 的紀錄，且收得到推播。LINE（小號）：#8 開啟後按 Google 登入，看是否進入 MicroG RE 帳號選擇 | 確認 #4 的時機足夠、Firebase 沒有 c2dm／iid 以外的推播呼叫、androidx 的 fallback 是否成立 |
| 13 | git | `git init`，在 `main` 上 commit 範本；實作開分支 `feat/gms-redirect` | — |

## 未驗證的前提

| 前提 | 影響 | 驗證方式 |
|---|---|---|
| `onPackageLoaded` 早於 app 第一次建立 GMS intent | 太晚的話，第一次 bind 會走 Google Play 服務 | #12 的 log |
| 模組可以呼叫 hidden API `ActivityThread.getPackageManager()` | 不行的話，#4 要改成在第一次 hook 觸發時，才用 `ActivityThread.currentApplication()` 檢查 | #12 的 log |
| androidx 拿不到 `credential` service 時，會 fallback 到 Play 服務實作 | 不成立的話，#8 無效 | LINE 實測，或讀 androidx `CredentialProviderFactory` 原始碼 |
| MicroG RE 實作了 Identity `GetSignInIntent` | 沒實作的話，Identity API 與 #8 都登入不了 | LINE 實測 |
| FCM register3 不比對簽章 | 不成立的話，推播分流沒有意義 | 智生活實測 |
