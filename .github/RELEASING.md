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

On Arch Linux:

```sh
sudo pacman -S --needed jre-openjdk-headless
keytool -genkeypair -v -keystore ~/air-release.jks -alias air -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 ~/air-release.jks > ~/air-release.b64   # paste this file's contents as SIGNING_KEY
```

This is done once, on any computer; it doesn't have to be the one you develop on. Back up the `.jks` file and its
password together (a password manager works), then delete the `.b64` copy.

## One-time: add the repository secrets

In GitHub, open Settings > Secrets and variables > Actions > New repository secret:

| Secret | Value |
|---|---|
| `SIGNING_KEY` | the base64 keystore |
| `KEY_STORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `air` |
| `KEY_PASSWORD` | the key password (the keystore password, unless you set a different one) |

Then re-run the failed workflow from the Actions tab.

