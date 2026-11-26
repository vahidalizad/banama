# 30nama integration research for Scenic Home

November 23, 2026. Scope covers the TV web client 1.2.0, Hybrid 1.4.3, and the published `30nama-sdk` 0.14.3. The SDK's TypeScript declarations describe the actions and response models for sign-in, discovery, My Lists, playback, and downloads. [SDK package](https://www.npmjs.com/package/30nama-sdk/v/0.14.3)

## What worked in the browser

- After the account holder approved the TV QR sign-in, the TV app reached Home and loaded its Watch List row. Searching for `Interstellar` opened title `7583` and its official player. Playback advanced and the quality menu listed **2160p**. [TV client](https://tv.30app.store/)
- Hybrid Precise Search returned `Interstellar` and other matches while signed out. Its UI opened the same title and reached the Download Links screen. Hybrid stayed at its separate sign-in prompt through the end of testing. A direct page load at `/single/7583/download` returned `404 NoSuchKey` from the host, while the same route worked through in-app navigation. [Hybrid client](https://hybrid.30app.store/)
- Hybrid's SDK request logs showed the browser using `https://world.hana-api.com/api/v2` after trying the bundled `https://ir.kazem-api.com/api/v2` default. A direct search POST to the default without client credentials returned a CDN `403`, so that result only establishes that the request failed at the edge. The live authenticated observations below refer to the official browser clients. [Hybrid entry bundle](https://hybrid.30app.store/_nuxt/CfyJXxa-.js)
- A native HTTPS probe on the TCL used the SDK's `ANDROIDTV` client identifier with the world endpoint. Both `test` and `qrlogin_code` returned HTTP 200 and a successful API response. The equivalent desktop probe received a Cloudflare challenge. Scenic Home completed native pairing on the TCL and restored the signed-in Watchlist after a cold launch.

## Shared request format

Both clients use `POST {apiBase}/action/{action}` with a UTF-8 `application/x-www-form-urlencoded` body. The SDK supplies a platform-specific `c-api-key`, `c-version-number`, and `c-language` (`fa` or `en`), plus `c-token` when signed in. The TV web app configures `WEBTV` and Hybrid configures `DESKTOP`. `test` checks connectivity and `endpoint` participates in endpoint discovery. The SDK also declares an `ANDROIDTV` platform. 30nama's native client configuration and supported use by Scenic Home need confirmation. Keep the user's session scoped to the configured API hosts.

The clients commonly use the JSON envelope `success`, `result`, and `msg`, and also check `error`. Check the HTTP status and application result independently. An example for an authorized client is below. The form body must be URL-encoded, including Persian text and any JSON-valued fields.

```http
POST /api/v2/action/search HTTP/1.1
Host: <configured API host>
Content-Type: application/x-www-form-urlencoded
c-api-key: <issued client key>
c-version-number: <configured client version>
c-language: en

query=Interstellar&streamonly=false&page=1
```

The examples and tables describe contracts inferred from the shipped clients. Success in the browser verifies the corresponding user flow, and the live request logs provide additional evidence for Hybrid where noted. [TV bundle](https://tv.30app.store/bundle.js) · [Hybrid entry bundle](https://hybrid.30app.store/_nuxt/CfyJXxa-.js)

## First-run sign-in

1. Call `qrlogin_code` with an empty form. Read `result.code` and `result.url`. Render the URL as a QR code and show it as text for manual entry.
2. The account holder signs in and approves the pair on the phone. Hybrid's activation page calls `qrlogin_auth` with `auth_code=<code>` from its already signed-in session. Scenic Home can display the URL supplied by the service and leave the approval on that page.
3. Call `qrlogin_login` with `auth_code=<code>` about every five seconds while awaiting approval. The clients stop automatic polling after about 60 seconds. The TV client can restart polling with its **Check again** control. Server-side code expiration and failure responses need verification.
4. On `result.token`, use that value as `c-token` and validate with `user` (empty form). The client reads `user_allowed_to_stream`, `user_allowed_to_download`, and `user_expire`. Persist the session with Android Keystore-backed encryption and reuse it on launch. Distinguish a rejected session from temporary connectivity failure. `logout` sends an empty form and the launcher should clear its saved session when the user signs out.

The SDK also exposes `captcha` (empty), `login` (`user_login`, `user_password`, `recaptcha_response`), `otp` (`user_login`, `recaptcha_response`), and `otp_verify` (`user_login`, `user_code`). The official flows handle any CAPTCHA and OTP. QR pairing fits the first-launch remote UX and keeps password entry on the phone. The inspected action list has no separate refresh-token call. [TV bundle](https://tv.30app.store/bundle.js) · [Hybrid QR approval page code](https://hybrid.30app.store/_nuxt/DyqeWqUZ.js)

## Catalog, library, and account actions

Each name maps to `/action/{name}` under the API base. A dash means an empty form.

| Action | Form fields | How the client uses the result |
| --- | --- | --- |
| `home`, `home_stream`, `menu` | — | Catalog rows, streaming rows, and navigation data. TV primarily uses `home_stream`. |
| `search` | `query`, `streamonly`, `page` | Accepts English or Persian text and IMDb IDs. The response includes `result.posts`, `page`, `pages`, and `total`. TV sends `streamonly=true` and starts at page 1. Hybrid Precise Search sends `false`. |
| `advanced_search_parameters` | — | Available filter values. Use server-provided values for genre, quality, and other enumerations. |
| `advanced_search` | `parameters` as JSON in a single form field, `orderby`, `order`, `page` | `result.posts`, `page`, `pages`, and `total`. SDK defaults are `relevant`, `DESC`, and page 1. The Hybrid form can override the sort. |
| `archive` | `slugs` as an array, `streamonly`, `page`, `orderby`, `order` | Genre and category browse results. The SDK uses `qs.stringify`, so the array keys serialize as `slugs[0]`, `slugs[1]`, and so on, URL-encoded in the request. |
| `single` | `post_id` | `result` contains title metadata, `options`, subtitles, and related details. `options.is_series` drives series UI. |
| `user_mylist` | — | Membership for `watchlist`, `favorite`, `notify`, and `downloadlist`. TV checks `result.watchlist.post` for membership by title ID. |
| `user_list` | `action=list`, `group=watchlist`, `page=1`, `orderby=add_list`, `order=DESC` | `result.posts` and pagination. Hybrid uses these fields for its library tab. |
| `user_list` | `action=add`, `group`, `post_id` | SDK exposes this as a toggle. The declared response is `result.action` (`add` or `remove`) and `result.group`. Read back on ambiguous failure to avoid a second toggle. |
| `continue_watching` | — | `result.posts[].data` carries the season, episode, and time in seconds. The saved time can be zero. The per-title stream response can also carry a resume entry for titles absent from this row. |
| `continue_watching_dismiss` | `id` | Dismisses the resume entry using the selected post ID. |
| `observer` | `post_id`, `file_id`, `user_id`, `time`, `duration`, `token` | Playback progress. `time` and `duration` are seconds. This token comes from the playback response, separate from `c-token`. The player reports at approximately 30-second boundaries. |
| `notification`, `notification_read` | `page` (default 1), `notification_id` respectively | Notification list and marking a notification read. The SDK accepts `all` for marking all read. |
| `stream_servers`, `checks` | Empty form for `stream_servers`, `key` for `checks` | SDK helpers. Response behavior remains unverified. |
| `offer`, `plans`, `paypal`, `shaparak`, `voucher` | `offer` empty, `plans` uses `gateway` and `discount_code`, checkout uses `plan_id` and `discount_code`, `voucher` uses `voucher_code` | Subscription and redemption actions present in the SDK. Account holders can manage purchases through 30nama's own checkout. |

Use `posts[].id` as `post_id` throughout. The title model includes `post_type`, `title.english`, `title.local`, `image`, `score`, and `options`. Hybrid's advanced form includes `type`, `genre`, `min_year`, `max_year`, `imdb_rate`, `country`, `language`, `quality`, `encoder`, `stream`, `x265`, `3D`, `actor`, `director`, `writer`, `age`, and `channel`. [Hybrid precise search code](https://hybrid.30app.store/_nuxt/ClgfveCH.js) · [Advanced search code](https://hybrid.30app.store/_nuxt/WsPHRXAs.js) · [Library code](https://hybrid.30app.store/_nuxt/PbfNULJj.js)

## Playback and downloads

| Action | Form fields | Fields consumed by the client |
| --- | --- | --- |
| `stream` | `post_id`, optional `free_stream` when the title exposes the free flow | `result.hls[]` contains `info.file_id`, `file.m3u8`, `file.subtitle`, and `options.token`. `result.continue_watching` carries the saved file and time. Optional `options.intro_start`, `options.intro_end`, and `options.end` mark the intro and the start of credits on that stream. The TV client reads them as `HH:MM:SS`. |
| `download` | `post_id`, `free_download` derived from the site's free flag | Hybrid reads `result.download`. Movies use an array of release rows with `dl`; series can use a map of season groups with `link[]` entries containing `episode` and `dl`. Rows can carry `quality`, `resolution`, `encoder`, `size`, `tags`, `mediainfo`, and subtitle information. Hybrid checks `user_allowed_to_download` on the normal paid route. |

Read `options.free_stream` and `options.free_download` from title details when choosing the playback or download mode. Keep temporary media URLs out of diagnostic logs and resolve them again when playback access expires. The SDK declares `file.m3u8` as an array of objects with `default`, `2ch`, `6ch`, and `label` fields. The TV code selects a URL using the preferred audio channel. [Hybrid player code](https://hybrid.30app.store/_nuxt/Df-Cu19X.js) · [Hybrid downloads code](https://hybrid.30app.store/_nuxt/BQqBFT7X.js) · [SDK response declarations](https://www.npmjs.com/package/30nama-sdk/v/0.14.3)

On the TCL, the native API returned 720p and 1080p download releases for both seasons of *The Gentlemen* (`630434`). The sampled HLS master also topped out at 1080p. The website trace used a separate v1 download API at `interface.30nama.com`. That route returned HTML in the native client probe without a website session, so Scenic Home continues to use the verified Android TV API. Two UHD Matroska releases from the TV API returned `206 Partial Content` at byte 0 and at a 1 MiB offset.

### 4K-first playback

1. Try the highest-bitrate compatible 4K HLS track first. If a seekable 4K download is unavailable, start the highest compatible streaming resolution automatically. A cropped 4K file can be 3840 pixels wide with fewer than 2160 active lines.
2. For an entitled 4K download, select an actual file using resolution and media information. Probe the fresh HTTPS URL with a small ranged `GET` such as `Range: bytes=0-65535`. Check for `206 Partial Content` and a matching `Content-Range`. A `200` on that GET means the server ignored the range, so close the probe promptly. Check a nonzero offset and seek behavior before relying on the URL for progressive playback.
3. Rank seekable 4K releases by the total bytes reported in `Content-Range`, largest first. List the result as **4K Download** alongside supported stream qualities. Media3 buffers as it plays and seeks with byte ranges. Keep the playback position and pause state when switching sources. Chapter times are specific to the streaming file.

References for the player and HTTP checks. [Media3 HLS](https://developer.android.com/media/media3/exoplayer/hls) · [Media3 progressive media](https://developer.android.com/media/media3/exoplayer/progressive) · [Media3 track selection](https://developer.android.com/media/media3/exoplayer/track-selection) · [HTTP range requests](https://www.rfc-editor.org/rfc/rfc9110.html#name-range-requests)

## Scenic Home integration order

1. Request a pairing link through the native Android client and display it as a TV QR code. Show a clear setup or retry state when native sign-in is unavailable. The signed-in TV browser served `/#/single/7583` for details, `/#/stream/7583` for playback, and `/#/myLists` for My Lists.
2. Use one launcher search input with 30nama title results and an **Open in YouTube** action for the query. For a title trailer, pass the title and year to the YouTube search action. Use YouTube's official Data API `search.list` with `part=snippet`, `type=video`, and `q` only if inline YouTube results become a requirement. Leave the launcher’s existing installed-app grid available. [YouTube Data API](https://developers.google.com/youtube/data/reference/rest/v3/search/list)
3. Use the watchlist as a launcher row and fetch title details on selection. Debounce search, discard stale results, and clear account-specific state on disconnect.
4. Use the stream episode list and saved season and episode to select the next playable episode for each watched series on the Watchlist. Report HLS playback with `observer`. Keep resume positions for direct-download playback on the TV, since those releases don't supply an observer token.

The native player reached `3840 × 2080` on the TCL with *Clashing Through the Snow*. Session expiry, watchlist writes, and seeking across release formats remain open.
