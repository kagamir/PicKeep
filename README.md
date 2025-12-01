# PicKeep - Zero-Knowledge Encrypted Photo Backup 🔐📸

PicKeep is an Android app for **zero-knowledge encrypted photo backup**.  
All photos and metadata are encrypted locally before upload, so the server **cannot read or decrypt your data**. 🧱

---

## What You Get (For Users) ✨

- **Private by design**: End‑to‑end encryption with per‑file keys, zero-knowledge server.
- **Automatic backup**: Incremental sync of new/changed photos and videos.
- **Standard storage**: Works with WebDAV providers (Nextcloud, ownCloud, etc.).
- **Multi‑device recovery**: 12‑word BIP39 mnemonic for restoring your vault.

### Basic Usage 🚀
- **First use**
  - Install app → follow the setup wizard.
  - Set a strong password (≥ 12 chars) and write down the 12‑word mnemonic (offline!).
  - Grant photo/media permissions.
- **Configure storage**
  - In **Settings → WebDAV**, fill in server URL, username, password.
  - Tap **“Test connection”**; if it passes, tap **Save**.
- **Backup**
  - Enable **Auto sync** (interval 1–24 hours, default 12).
  - OR tap the **Sync** button on the main screen to start immediately.
  - You can pause / resume / cancel sync and see per‑file progress.
- **Restore on a new device**
  - Install app → choose **“Restore with mnemonic”**.
  - Enter the same 12‑word mnemonic + original password.
  - Configure the same WebDAV; the app will re‑download and decrypt all data.

---

## How Security Works (For Security‑Conscious Users) 🔐

This section explains **how your data is protected**, in the order it happens.  
All details are included so you can judge whether it matches your security requirements.

### 1. Keys & Secrets 🧠

- **Master Key**
  - Derived from a 12‑word **BIP39 mnemonic** using the BIP39 standard  
    (PBKDF2WithHmacSHA512, 2048 iterations).
  - The **first 256 bits of the seed** are used as the master key.
  - Only kept **in memory** while the app is unlocked; cleared when the app locks or goes to background for 5 minutes.
  - Can be exported/imported via the mnemonic for multi‑device use.

- **Key Encryption Key (KEK)**
  - Derived from the **user password** using **PBKDF2‑HMAC‑SHA256**, 100,000 iterations.
  - Used to **encrypt (“wrap”) the master key**, which is then stored in `EncryptedSharedPreferences`.
  - When changing password, only the wrapped master key is re‑encrypted; **files remain untouched**.

- **Content Encryption Key (CEK)**
  - Each file gets its own **random 256‑bit CEK**.
  - CEK is encrypted with the master key and stored in the local Room database.
  - Design allows for key rotation and revocation at the file level.

### 2. File Encryption Flow 📦

For each file, the app performs:
1. **Hashing**
   - Computes a **SHA‑256** hash of the plaintext file.  
   - Used for deduplication and path generation (never uploaded in plaintext).
2. **Encryption**
   - Algorithm: **AES‑256‑GCM**.
   - Mode: streaming encryption in **64KB chunks**.
   - IV: **random 12‑byte IV** per file.
   - Authentication: **16‑byte GCM tag** appended to the ciphertext.
   - File format:
     - `[version (1 byte)][IV (12 bytes)][ciphertext ...][tag (16 bytes)]`.
3. **Upload**
   - Large files (>10MB) and videos are split into **5MB chunks**.
   - Upload uses WebDAV `PUT` with chunked uploads (server must support this).

### 3. Metadata & Privacy 🕵️

- **Metadata encryption**
  - Original filename, timestamps, geolocation (EXIF), MIME type, etc. are all encrypted.
  - Encrypted with the **same CEK** as the file.
  - Stored and uploaded as a separate `.meta` file, so the server cannot index or search metadata.

- **Remote filenames**
  - Derived from file content using **HMAC‑SHA256**, with a salt derived from the master key.
  - This hides the real filename and prevents simple hash‑based recognition.

- **Server visibility**
  - The server sees only:
    - Random‑looking encrypted blobs.
    - Random‑looking metadata blobs.
    - Opaque paths built from HMACs.
  - The server **never sees**:
    - Plaintext photos or metadata.
    - Plaintext hashes of files.
    - Your mnemonic or password.

- **Device identity**
  - Each device has a separate **UUID** used only for conflict resolution.
  - Not linked to any real‑world identity.

---

## How It Syncs (High‑Level) 🔁

- **Change detection**
  - Uses Android **MediaStore** to detect new/modified photos and videos by timestamp and size.
- **Incremental sync**
  - Only changed files go through hash → encrypt → upload → metadata upload pipeline.
- **Concurrency & reliability**
  - Concurrency (1–3 parallel uploads) auto‑adjusts based on available memory.
  - Retry with **exponential backoff**, up to 5 attempts; HTTP 4xx errors are not retried.
- **Background execution**
  - Uses **WorkManager** for scheduled background sync.
  - You can configure:
    - Sync interval (1–24 hours).
    - Network conditions (Wi‑Fi only).
    - Charging state (only when charging).

---

## For Developers 🧩

### Stack & Dependencies 📚

- **UI**: Jetpack Compose (Material3).
- **Storage & DB**: Room, EncryptedSharedPreferences, WebDAV (via OkHttp).
- **Background**: WorkManager.
- **Crypto**: Bouncy Castle (AES‑GCM), BIP39.
- **Serialization & media**: Kotlinx Serialization, AndroidX ExifInterface.

### Code Layout (Short Version) 📁

The main modules live under `app/src/main/java/net/kagamir/pickeep/`:

- `crypto/` – BIP39, key derivation, CEK management, file & metadata encryption, master key store.
- `data/` – Room entities, DAOs, and repositories.
- `storage/` – `StorageClient`, WebDAV client, chunked uploader.
- `monitor/` – MediaStore and file system observers, photo scanner.
- `sync/` – Sync engine, per‑file upload task, global sync state.
- `worker/` – WorkManager workers and scheduling utilities.
- `ui/` – Screens (setup, unlock, sync status, settings), navigation graph, theme.

To get started as a developer:
- Open the project in **Android Studio Hedgehog (2023.1.1+)**.
- Make sure you have **JDK 11+**, **Android SDK 36 (compileSdk)**, minSdk 31 (Android 12), targetSdk 33.
- Run the app on a device with photo library access and test against a WebDAV server.

---

## Roadmap & Status 📌

- [ ] Support additional storage backends (S3, custom APIs).
- [x] Encrypted in‑app photo viewer.
- [ ] Argon2id key derivation (planned replacement/option for PBKDF2).
- [x] Internationalization (I18N).

---

## License 📜

GNU GENERAL PUBLIC LICENSE VERSION 3

---

## Disclaimer ⚠️

This app is currently a **proof of concept** for a zero‑knowledge encrypted photo backup solution.  
Before using it in production, you should have:

1. A complete security audit.
2. Robust error‑handling and recovery procedures.
3. A clear database migration strategy.
4. Comprehensive unit and integration tests.
5. Performance optimization and stress testing.

Do **not** rely solely on this app for critical data. Always maintain separate local backups. 💾

