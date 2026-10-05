# Releasing

Pushes to `main` that change `app/version.properties` build a signed APK and publish it as a GitHub Release
(`.github/workflows/release.yml`). Every release must be signed with the same key, or installed copies can't update.

## One-time: create the signing key

Generate a keystore (PowerShell; `keytool` ships with Android Studio). Keep the file and password somewhere safe and
outside the repo. If you lose them, existing installs can never be updated again.

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v `
  -keystore $HOME\air-release.jks -alias air -keyalg RSA -keysize 4096 -validity 36500
```

Copy it to the clipboard as base64:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\air-release.jks")) | Set-Clipboard
```

On macOS/Linux: `keytool ...` with the same arguments, then `base64 -w0 air-release.jks` (macOS: `base64 -i air-release.jks`).

## One-time: add the repository secrets

In GitHub, open Settings > Secrets and variables > Actions > New repository secret:

| Secret | Value |
|---|---|
| `SIGNING_KEY` | the base64 keystore |
| `KEY_STORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `air` |
| `KEY_PASSWORD` | the key password (the keystore password, unless you set a different one) |

Then re-run the failed workflow from the Actions tab.

## Switching from a locally built APK

Local builds are signed with your machine's debug key, so the first release won't install over them. Export a backup
(Settings > Import/Backup), uninstall, install the release, and import the backup. Mail app passwords aren't in
backups, so re-enter them. Updates install in place from then on.
