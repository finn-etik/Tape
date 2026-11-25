# TAPE

A quiet music and audiobook player for Android. It plays files on the phone and
files stored in [Drime](https://drime.cloud).

- **Music**: albums and songs from the phone (MediaStore) and from Drime folders you add to
  Music (a folder of audio = an album; `Artist/Album` folders are understood), shuffle, repeat.
- **Playlists**: your own, mixing songs from the phone and Drime. Long-press any song (or use the
  + in the player) to add it; reorder and remove with a long-press inside the playlist.
- **Books**: pick a folder and every subfolder becomes a book (or a single `.m4b`/`.mp3` file).
  TAPE remembers your place per book, keeps its own speed per book (0.8×–2×), and steps
  back 10 s when you return after a break.
- **Drime**: browse your Drime folders and stream anything playable. Add a folder to Music, or to
  Books as one book or as a library whose subfolders are books.
- **Android Auto**: Books (resume with one tap), Playlists, Albums and Songs, plus search and
  voice ("play The Lantern Path on TAPE").
- Sleep timer that fades out (minutes or end of chapter), lock-screen, notification and
  headset controls, headset "play" resumes your last audiobook even after the app was closed,
  light and dark themes.

Kotlin · Jetpack Compose · Media3 (ExoPlayer + MediaSession) · OkHttp · Coil.
minSdk 26, targetSdk 35.

## Get the app

Every push to `main` is built by GitHub Actions. Open the repo's **Releases › Latest build** on
your phone and tap `tape-debug.apk` to install. Builds share one debug signing key
(`app/debug.keystore`), so a new build installs over the old one and keeps your progress.

## Build locally

1. Open the folder in Android Studio (Ladybug or newer) and let it sync.
2. Optional: copy `local.properties.example` to `local.properties` and fill in `drime.clientId`
   only if you want the OAuth sign-in (see below). Tokens need no setup.
3. Run the `app` configuration.

## Connecting Drime

### With a personal access token (the default)

In Drime: **Settings › Developers › Create token**. Choose **Restricted**, set **Files** and
**Folders** to **Read**, leave everything else at *No access*. Paste the token in TAPE's Drime tab.

The token is stored encrypted on the phone and never expires; delete it in Drime to cut TAPE off
immediately. Read-only scope means a leaked token can't change or delete anything.

### With OAuth (optional, for sharing TAPE with others)

The full OAuth 2.0 + PKCE sign-in is in `DrimeLogin`. It stays hidden until the build has a
client id; then the Drime tab also shows "Sign in with Drime instead".

#### Register the app (once)

At <https://app.drime.cloud/developers>, create an application:

| Setting | Value |
|---|---|
| Type | Mobile (public client, **no secret**) |
| Redirect URL | `http://127.0.0.1/callback` |
| Permissions | `files.read`, `folders.read` |
| Access | Whole Drive, or "own folder only" if you want TAPE limited to `Applications/TAPE` |

Put the client id in `local.properties` as `drime.clientId=…`.

#### Why a loopback redirect

Drime refuses custom-scheme redirects like `tape://callback` (another app could register the
same scheme and catch the code). It does accept `http://127.0.0.1` and ignores the port, as
RFC 8252 recommends for native apps. So TAPE:

1. makes a PKCE verifier/challenge (S256) and a random `state`,
2. opens a one-shot server on `127.0.0.1:<random port>`,
3. opens Drime's consent page in a Custom Tab,
4. reads `code` + `state` from the browser's request to `/callback`, answers with a small
   "Drime is connected" page (with a *Back to TAPE* button), and
5. trades the code for tokens.

The `tape://auth` link on that page only brings TAPE to the front; it carries no data.

If you later prefer an https redirect, an Android App Link on your own domain works too; swap
`DrimeLogin` for an App Link handler and register that URL instead.

#### Tokens

- Access tokens last 1 hour; refresh tokens 90 days and **rotate**. Drime revokes everything if a
  refresh token is used twice, so refreshing is serialized behind one lock and the new token is
  written (`commit()`, encrypted) before it is used. See `DrimeSession`.
- `DrimeAuthInterceptor` adds the bearer token only for `app.drime.cloud`; OkHttp strips it if a
  download redirects to another host.
- **Personal access token** (Drime › Settings › Developers) is the alternative: paste it in the
  Drime tab. Handy while developing.

### Streaming

Tracks play from `GET /api/v1/file-entries/download/{hash}` through a 1 GB on-disk LRU cache,
so replays and seeks within played parts are instant. MP3s without a seek index use
constant-bitrate seeking.

Before release, Drime reviews the app (publisher name, description, website, privacy policy);
until then it works for up to 25 users. See <https://docs.drime.cloud/oauth/going-to-production.md>.

## Web preview

`preview/index.html` is a clickable copy of the app's screens that runs in any browser: open the
file directly. Sample music and books play generated audio; you can add your own files; Drime is
simulated with sample folders.

## Layout

```
app/src/main/java/app/tape/
  TapeApp.kt            Application + hand-wired dependencies (AppGraph)
  MainActivity.kt
  drime/                OAuth (DrimeLogin), tokens (TokenStore, DrimeSession), API, folder browser
  library/              LocalMusic (MediaStore), BookLibrary (SAF + Drime), ProgressStore
  playback/             PlaybackService (Media3 session), PlayerConnection (UI side), SleepTimer
  ui/                   Compose screens; theme/ holds the palette
preview/index.html      Web preview of the UI
.github/workflows/      CI: builds the APK and publishes "Latest build"
```

## Not yet

- Chapters *inside* a single `.m4b` (one file plays as one long chapter for now).
- Keeping a Drime book fully offline on purpose (today it's cached as you listen).
- Cover art in Android Auto's browse lists (the now-playing screen in the car does show it).

## License

TAPE is released under the [Apache License 2.0](LICENSE); see [NOTICE](NOTICE).
Every library it uses is listed with its license in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md),
and the app shows them under the ⓘ in the top bar. All are Apache 2.0 or MIT; nothing copyleft.
