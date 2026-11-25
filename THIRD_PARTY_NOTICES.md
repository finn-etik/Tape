# Third-party notices

TAPE is licensed under the [Apache License 2.0](LICENSE). It is built on the open-source
components below. All of them are under licenses compatible with Apache 2.0, and none is
copyleft (no GPL/LGPL), so the app can be shared or published freely as long as these notices
travel with it. The app shows them under **About › Open-source licenses**.

## Shipped inside the app

| Component | Used for | License |
|---|---|---|
| Kotlin standard library, kotlinx.coroutines (core, android, guava) | Language runtime, background work | Apache 2.0 |
| AndroidX Core, Activity, Lifecycle | App foundations | Apache 2.0 |
| Jetpack Compose (UI, Foundation, Material 3, Material Icons Extended) | The whole interface, icons | Apache 2.0 |
| AndroidX Media3 (ExoPlayer, Session, Database, OkHttp data source) | Playback, notification, lock-screen and headset controls, stream cache | Apache 2.0 |
| AndroidX Browser | Custom Tab for the optional Drime sign-in | Apache 2.0 |
| AndroidX Security Crypto, with Google Tink | Encrypted storage of the Drime token | Apache 2.0 |
| OkHttp, Okio (Square) | Networking to Drime | Apache 2.0 |
| Coil (Coil Contributors) | Loading cover art | Apache 2.0 |
| Guava, failureaccess, listenablefuture (Google; via Media3) | Futures used by Media3 | Apache 2.0 |
| Error Prone annotations, J2ObjC annotations, JSpecify (via Guava) | Compile-time annotations | Apache 2.0 |
| Checker Framework qualifiers (via Guava) | Compile-time annotations | MIT |

## Used only to build or preview

| Component | Used for | License |
|---|---|---|
| Gradle wrapper | Building | Apache 2.0 |
| Android Gradle Plugin, Kotlin Gradle Plugin | Building | Apache 2.0 |
| GitHub Actions: checkout, setup-java, setup-gradle, upload-artifact | CI builds | MIT |
| Roboto (Google Fonts, loaded by `preview/index.html`, not bundled) | Web preview type | SIL Open Font License 1.1 |
| Material icon shapes, redrawn in `preview/index.html` | Web preview icons | Apache 2.0 |

## Services

Drime is used through its public API, with your own account and token. No Drime code is part
of TAPE. The name "Drime" belongs to its owner and is used only to say what TAPE connects to.

## Note on maintenance

`androidx.security:security-crypto` has been deprecated by Google. It still works and is
Apache 2.0. Replacing it with a small Android Keystore wrapper is on the to-do list.
Dependabot (`.github/dependabot.yml`) opens pull requests for library updates; each one is
built by CI before it can be merged.
