# MicroGLogin

English | [繁體中文](README.zh-TW.md)

An Xposed module that makes Google Sign-In work in apps re-signed by LSPatch. Used together with LSPatch's "GMS replacement" option, it routes the app's calls to Google Play services to a replacement such as MicroG RE (`app.revanced.android.gms`), filling in the parts that sign-in needs but the LSPatch loader does not do.

Written against libxposed API 102. Requires Android 10 or later.

## Why

A patched app is signed with the patcher's key. Google Play services compares the caller's signature in its own process, which no longer matches the SHA-1 registered in the Google API Console, so sign-in fails (`UNREGISTERED_ON_API_CONSOLE`). Hooks inside the app cannot reach that check, so the work is split:

| Part | Handled by | Reason |
|---|---|---|
| Write `<vendor>.android.gms.SPOOFED_PACKAGE_SIGNATURE` (the original signature) into the manifest; the replacement reports it to Google | The "GMS replacement" option of [LSPatch](https://github.com/JingMatrix/LSPatch) | The manifest can only be changed at patch time |
| Redirect packages, components and provider authorities to the replacement; rename C2DM in the manifest | LSPatch loader (same option) | — |
| Action renaming, redirection at framework exits, chimera bind unwrapping, `AccountManager` tokens, Credential Manager interop | This module | The LSPatch loader does not rename actions, but MicroG RE declares its sign-in, identitycredentials, phenotype and other services only under the renamed actions; the loader alone cannot sign in (tested with 智生活) |

With rooted LSPosed the app is not re-signed, and this module is not needed.

## Installation

1. Install MicroG RE and sign in to your Google account there. When the app requests a token, the module uses the MicroG RE account with the same name, so sign in to the same account.
2. Install the MicroGLogin APK.
3. Patch the target app with LSPatch: set "GMS replacement" to MicroG RE (`app.revanced.android.gms`) and select MicroGLogin as a module. In integrated mode, also add the `QUERY_ALL_PACKAGES` permission so the app can see MicroG RE.
4. Uninstall the original app, then install the patched one. The signatures differ, so it cannot be installed over the original.

Where push messages go depends on the C2DM names in the manifest. With LSPatch's GMS replacement option, C2DM is renamed to `<vendor>.android.c2dm.*` and push goes to the replacement; the module detects this from the permission the app requests (`<vendor>.android.c2dm.permission.RECEIVE`) and derives the replacement's package from it. Without the rename (for example, a patch that only writes the signature declaration), push stays with Google Play services, and the redirect target defaults to MicroG RE.

## Behavior

| Call | Destination |
|---|---|
| Packages, components and provider authorities of `com.google.android.gms` and `com.google.android.gsf` | The replacement (`<vendor>.android.gms`) |
| `com.google.android.gms.*` actions | Renamed to `<vendor>.android.gms.*` if the replacement declares the renamed action, otherwise kept as the Google name (MicroG RE 7.2.1 mostly has only renamed names; a few, such as `ads.identifier.service.START`, have only the original). Resolved via PackageManager and cached once per action |
| Push: `com.google.android.c2dm.*`, `com.google.iid.*`, `com.google.android.gms.cloudmessaging.*` | C2DM renamed: handed to the replacement, with C2DM actions renamed to `<vendor>.android.c2dm.*` (same as the LSPatch loader). Not renamed: kept with Google Play services (FCM does not check the caller's signature) |
| Chimera binds from newer Play services libraries (`BoundService.START` + `chimera-action:<action>`, targeting a GMS component directly) | Unwrapped back to the original action, then redirected to the replacement (which has no such components); push kept with Google Play services binds as-is |
| `common.account.CHOOSE_ACCOUNT` | The system account picker, listing only the replacement's accounts (`com.google` in extras such as `allowableAccountTypes` is replaced with the replacement's account type); the chosen account's type is changed back to `com.google`. Reason: a re-signed app cannot get tokens for Google Play services accounts, and fails if the replacement has no account with the same name |
| `AccountManager.getAuthToken`, `blockingGetAuthToken` with account type `com.google` | Uses the MicroG RE account with the same name (type determined by MicroG RE's authenticator); otherwise Google Play services rejects it with `UNREGISTERED_ON_API_CONSOLE` |
| `com.google.android.gms.chimera`, `com.google.android.gms.fonts` providers | Kept with Google Play services (MicroG has no Dynamite modules; fonts are unrelated to signatures, and redirecting causes an authority mismatch) |
| Intents targeting the app's own components (e.g. `SignInHubActivity`) | Action unchanged |
| Android 14+, Credential Manager requests with a Google ID option (`CredentialManager.getCredential`) | Handled by the module inside the app: launches the replacement's `ASSISTED_SIGNIN` from the calling Activity (with SafeParcel-encoded `GoogleSignInOptions` and `GetSignInIntentRequest`), and the replacement shows its account dialog; the result is collected in `Activity.dispatchActivityResult` and assembled into a `GetCredentialResponse`. Passkey and password options in the same request are dropped; requests without Google ID go to the system as usual. Reason: the system hands Google ID requests only to system providers installed in `/system`, so a normally installed MicroG RE never receives them (microg/GmsCore#3694) |
| Android 14+, `CredentialManager.clearCredentialState` | Passed to the system as usual; a failure is reported as success. Reason: Google ID is handled by the module, so the system has no Google state to clear, and a failure only means the device has no credential provider (e.g. a system without Google Play services), which LINE does not handle and crashes on |
| Android 13 and below, the Google ID credential type `com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL` (androidx goes through Play services' identitycredentials) | Changed to the `<vendor>.android.libraries...` name the replacement recognizes when sent, and back on return; the Bundle returned by MicroG RE is converted to the `GetCredentialResponse`/`GetCredentialException` that androidx reads on Android 14+ |

Intents are rewritten in two places: the `Intent` constructors and setters, and just before the framework sends them, in `ContextImpl.bindServiceCommon`, `startServiceCommon` and `Instrumentation.execStartActivity`. After AOT compilation the setters may be inlined and escape the hooks; the rewrite before sending covers that.

If the replacement's package is not of the form `<vendor>.android.gms`, it is treated as a replacement that keeps Google's names, and only the package is changed. If the replacement is not installed or not visible to the app, the module does nothing.

## Supported Google sign-in methods

| API used by the app | Status |
|---|---|
| play-services-auth (`GoogleSignInClient`) | Supported |
| Identity API (`Identity.getSignInClient`) | Supported in principle, depending on MicroG RE's implementation |
| androidx Credential Manager + `googleid` (e.g. LINE, Speedtest) | On Android 14+ handled by the module, showing the replacement's account dialog (LINE 26.14.0 tested to obtain an ID token; Speedtest tested to open the dialog). `filterByAuthorizedAccounts` and `autoSelectEnabled` of `GetGoogleIdOption` are not supported; the dialog is always shown. Untested on Android 13 and below |
| Web OAuth (Custom Tab) | Does not go through GMS; works already |

## Limitations

- When push is kept with Google Play services, devices with only MicroG RE and no Google Play services do not receive push.
- If the Firebase API key has Android app restrictions, the app's FCM token request is blocked (`API_KEY_ANDROID_APP_BLOCKED`). Enabling signature bypass in LSPatch (level 1 or above) solves this.
- NPatch does not write `SPOOFED_PACKAGE_SIGNATURE`, so sign-in still fails under NPatch.
- LSPatch's "inject dex" option makes this module fail to load (`NoSuchMethodError: kotlin.Pair.component1()`): after injection the loader gets the app's R8-shrunk Kotlin standard library. Observed with LINE; disable inject dex when patching.
- When MicroG RE 7.2.2 asks for OAuth consent (e.g. LINE's Google Drive backup), `UnpackingRedirectActivity` crashes after opening the consent dialog (its theme is not AppCompat); allowing or denying reopens it, and the token never reaches the app. Approve the app in MicroG RE's settings beforehand to avoid this.
- The app's own signature checks and server-side integrity checks are out of scope.

## Build

```bash
./gradlew assembleDebug
```

The output is at `app/build/outputs/apk/debug/app-debug.apk`. Requires a JDK and the Android SDK (compileSdk 37).

## Debugging

```bash
adb logcat -c
```

```bash
adb logcat -s MicroGLogin
```

Clear first, start logging, then reproduce the problem. The logcat main buffer defaults to 256 KiB; on devices with heavy system logging it holds only the last few minutes, and the app's startup records are quickly pushed out.

At startup the module logs the framework name and version, whether MicroG RE is visible, and whether each hook group was installed. After that, every rewrite logs one debug line.
