# Third-Party Notices

OpenMesh bundles or depends on the components below. Versions are pinned in
`gradle/libs.versions.toml` unless a file is committed directly.

---

## hev-socks5-tunnel 2.20.0

- **Purpose:** userspace TUN → SOCKS5 engine used by the P1 data path.
- **License:** MIT.
- **Copyright:** Copyright (c) 2022 hev.
- **Artifact:** `vpn/tun2socks/libs/hev-socks5-tunnel-2.20.0.aar`
- **SHA-256:** `AE8A2E96B80B4ECCCCD037805A4BB4A278090C89DBCAE8BC6FCB7634E9A255B9`
- **Source commit recorded at selection:** `75dbc1e9ace6c70d3d1b94df7c283efe1de7effc`
- **ABIs in the AAR:** `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`
- **License text:** [`third_party/hev-socks5-tunnel/LICENSE`](third_party/hev-socks5-tunnel/LICENSE)

```
Copyright (c) 2022 hev

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## AndroidX / Jetpack Compose

- **Components:** `androidx.core:core-ktx`, `androidx.activity:activity-compose`,
  `androidx.lifecycle:*`, Compose UI, Material 3, test libraries.
- **Versions:** see `gradle/libs.versions.toml` (Compose BOM 2026.03.01,
  Core 1.18.0, Activity 1.13.0, Lifecycle 2.10.0).
- **License:** Apache License 2.0.
- **Copyright:** The Android Open Source Project.

Apache 2.0: https://www.apache.org/licenses/LICENSE-2.0

---

## Kotlin and Kotlin Coroutines

- **Components:** Kotlin standard library/runtime 2.3.20, Kotlin Coroutines
  1.10.2.
- **License:** Apache License 2.0.
- **Copyright:** JetBrains s.r.o. and contributors.

Apache 2.0: https://www.apache.org/licenses/LICENSE-2.0

---

## Android Gradle Plugin and Gradle

- **Components:** Android Gradle Plugin 9.0.1, Gradle wrapper 9.5.1.
- **License:** Apache License 2.0.
- **Copyright:** Google LLC / Gradle Inc. and contributors.
- Build-time only; not shipped in the APK.

Apache 2.0: https://www.apache.org/licenses/LICENSE-2.0

---

## JUnit 4.13.2

- **Purpose:** host-side unit tests only.
- **License:** Eclipse Public License 1.0.
- **Copyright:** JUnit contributors.

EPL 1.0: https://www.eclipse.org/legal/epl-v10.html

Not shipped in the APK.

---

## Bouncy Castle (declared, not used yet)

- **Components:** `org.bouncycastle:bcprov-jdk18on:1.83`,
  `bcpkix-jdk18on:1.83`.
- **Declared in:** `transport/build.gradle.kts` for the future TLS/identity
  work (P2). There is no source in that module and no Bouncy Castle code is
  currently exercised or shipped by the app.
- **License:** Bouncy Castle Licence (MIT-style).
- **Copyright:** The Legion of the Bouncy Castle Inc.

Review this dependency before P2. Remove it if the platform TLS stack is
sufficient.

License: https://www.bouncycastle.org/licence.html

---

## Node.js test server

- **File:** `tools/test-server.mjs`.
- **Dependencies:** none beyond Node.js built-in modules.
- Node.js itself is a development prerequisite and is not distributed with
  OpenMesh.

---

## WhatsApp

OpenMesh does not include, modify, decompile or redistribute WhatsApp code or
assets. WhatsApp is referenced only as the target package name
`com.whatsapp`. WhatsApp is a trademark of Meta Platforms, Inc.; OpenMesh is not
affiliated with or endorsed by Meta.

---

## OpenMesh's own license

The OpenMesh source has no license file yet. Add one before distributing source
or binaries. This notice does not grant rights to OpenMesh's own code.
