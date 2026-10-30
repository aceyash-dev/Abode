# Signing

Two keys exist, and they are not interchangeable.

## Debug key — committed, public, for nightlies only

`keystore/abode-debug.p12` is checked into this repository on purpose.

CI runners create a fresh debug keystore for every job, which gives every nightly
build a different signature — and Android refuses to install a package over one
signed with a different key, so each nightly needed an uninstall first. One shared
debug key means nightlies upgrade in place.

Alias `androiddebugkey`, password `android`. **This key is public. It must never
sign anything that goes to Play.**

## Release key — yours, never in this repository

Generated once, kept offline, backed up in at least two places. Losing it means
you cannot ship an update to an existing install; the app-signing key is the one
secret in this project that is genuinely unrecoverable.

Release builds read it from `keystore.properties` locally, or from environment
variables of the same names in CI:

| Secret | Contents |
| --- | --- |
| `KEYSTORE_BASE64` | the `.p12` file, base64-encoded |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_ALIAS` | `abode` |
| `KEY_PASSWORD` | the same as the store password (PKCS12 uses one) |

Producing the base64 for the secret:

```bash
base64 -w0 abode-release.p12 > keystore.b64
```

Wiring it into a workflow:

```yaml
      - name: Decode the keystore
        run: echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > release.p12

      - name: Build a signed release
        env:
          storeFile: release.p12
          storePassword: ${{ secrets.KEYSTORE_PASSWORD }}
          keyAlias: ${{ secrets.KEY_ALIAS }}
          keyPassword: ${{ secrets.KEY_PASSWORD }}
        run: gradle :app:assembleRelease -PgitSha=${{ github.sha }}
```

`app/build.gradle.kts` reads those names from `keystore.properties` first and the
environment second, and falls back to an unsigned release when neither is
present — so a fresh clone still assembles.

## Play App Signing

Enrol before the first upload. Google then holds the app-signing key and the
`.p12` above becomes the *upload* key, which can be reset through support if it
is lost. Without enrolment, a lost key is a lost app.
