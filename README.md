# PicKeep - Zero-Knowledge Encrypted Photo Backup 🔐📸

PicKeep is an Android app that provides a **zero-knowledge encrypted photo backup** service. All photos are encrypted locally end-to-end, and the server **cannot read any of your photo content**. 🧱

## Core Features ✨

### Zero-Knowledge Encryption 🔒
- **End-to-end encryption**: Photos and metadata are encrypted with **AES-256-GCM** before upload.
- **BIP39 mnemonic**: 12-word mnemonic phrase is used to derive the master key, supporting multi-device recovery.
- **Key derivation**: Uses **PBKDF2-HMAC-SHA256** (100,000 iterations) to derive a Key Encryption Key (KEK) from the user password.
- **Per-file keys**: Each file uses an independent Content Encryption Key (CEK), which is encrypted with the master key and stored.
- **Privacy-preserving filenames**: Remote filenames are based on an **HMAC-SHA256** of file content and reveal no metadata.

### Incremental Sync 🔁
- **Smart detection**: Monitors photo and video changes via MediaStore, checking modification time and size.
- **Chunked upload**: Large files (>10MB) and videos are automatically uploaded in **5MB chunks**.
- **Dynamic concurrency**: Automatically adjusts concurrency (1–3 parallel tasks) based on available memory.
- **Smart retry**: Exponential backoff retry mechanism (up to 5 times); HTTP 4xx errors are not retried.
- **State management**: Tracks full sync state, including hashing, encryption, upload and more.
- **Progress display**: Shows real-time upload progress and current step for each file. 📊

### WebDAV Support ☁️
- **Standard protocol**: Compatible with WebDAV services such as Nextcloud and ownCloud.
- **TLS encryption**: Uses HTTPS for secure transport.
- **Flexible configuration**: Supports custom server URL and authentication.

### Background Tasks ⚙️
- **WorkManager**: Uses Android WorkManager for background sync.
- **Smart scheduling**: Configurable sync interval (1–24 hours), network conditions (Wi‑Fi only), and charging state.
- **Foreground service**: Shows progress notification during sync, including current file and step.
- **App lock**: App auto-locks 5 minutes after going to background, and the master key is wiped from memory. 🔐

## Technical Architecture 🧩

### Dependencies 📚
- **Jetpack Compose**: Modern declarative UI.
- **Room**: Local database for photo metadata and sync state.
- **WorkManager**: Background task scheduling.
- **OkHttp**: HTTP client implementing WebDAV operations.
- **Kotlinx Serialization**: JSON serialization.
- **EncryptedSharedPreferences**: Secure storage for sensitive configuration (WebDAV credentials, etc).
- **Bouncy Castle**: Crypto implementation (AES-GCM).
- **AndroidX ExifInterface**: Read photo metadata (such as geolocation).

### Project Structure 📁
```
app/src/main/java/net/kagamir/pickeep/
├── crypto/                      # Crypto layer
│   ├── Bip39.kt                 # BIP39 mnemonic implementation
│   ├── KeyDerivation.kt         # Key derivation (PBKDF2)
│   ├── CekManager.kt            # CEK management (generation, encrypt, decrypt)
│   ├── FileEncryptor.kt         # File encryption (AES-256-GCM)
│   ├── MetadataEncryptor.kt     # Metadata encryption
│   ├── PhotoMetadata.kt         # Photo metadata model
│   └── MasterKeyStore.kt        # Master key management (lock, unlock, password change)
├── data/
│   ├── local/                   # Local data
│   │   ├── entity/              # Entities
│   │   ├── dao/                 # Data Access Objects
│   │   └── PicKeepDatabase.kt
│   └── repository/              # Repositories
├── storage/                     # Storage layer
│   ├── StorageClient.kt         # Storage client interface
│   ├── RemoteFileInfo.kt        # Remote file info
│   └── webdav/                  # WebDAV implementation
│       ├── WebDavClient.kt      # WebDAV client
│       └── ChunkedUploader.kt   # Chunked uploader
├── monitor/                     # Monitoring layer
│   ├── MediaStoreObserver.kt
│   ├── FileSystemObserver.kt
│   └── PhotoScanner.kt          # Photo scanner (MediaStore queries)
├── sync/                        # Sync engine
│   ├── SyncEngine.kt            # Sync engine (batch upload, concurrency control)
│   ├── UploadTask.kt            # Upload task (per-file upload pipeline)
│   └── SyncState.kt             # Sync state management (singleton)
├── worker/                      # Background workers
│   ├── PhotoSyncWorker.kt
│   └── WorkManagerScheduler.kt
└── ui/                          # UI layer
    ├── screen/                  # Screens
    │   ├── SetupScreen.kt       # Initial setup (create/restore account)
    │   ├── UnlockScreen.kt      # Unlock screen
    │   ├── SyncStatusScreen.kt  # Sync status screen (main)
    │   └── SettingsScreen.kt    # Settings screen
    ├── navigation/              # Navigation
    │   └── NavGraph.kt          # Navigation graph
    └── theme/                   # Theme
        ├── Color.kt             # Color definitions
        ├── Theme.kt             # Material3 theme
        └── Type.kt              # Typography
```

## Security Design 🔐

### Cryptographic Scheme 🧠
1. **Master Key**
   - Derived from a 12-word BIP39 mnemonic using the BIP39 standard (PBKDF2WithHmacSHA512, 2048 iterations).
   - Uses the first 256 bits of the seed as the master key.
   - Stored **only in memory** and cleared when the app is locked.
   - Can be exported as a mnemonic for multi-device recovery.

2. **Key Encryption Key (KEK)**
   - Derived from the user password using PBKDF2-HMAC-SHA256 (100,000 iterations).
   - Used to wrap (encrypt) the master key and store it in `EncryptedSharedPreferences`.
   - When changing password, only the wrapped master key is re-encrypted; files do not need to be re-encrypted.

3. **Content Encryption Key (CEK)**
   - A unique 256-bit random key is generated for each file.
   - CEK is encrypted with the master key and stored in the local Room database.
   - Supports key rotation and revocation.

4. **File Encryption**
   - Algorithm: **AES-256-GCM**.
   - Mode: streaming encryption (64KB chunks).
   - IV: random 12‑byte IV per file.
   - Authentication: includes a 16‑byte authentication tag to prevent tampering.
   - Format: `[version(1 byte)][IV(12 bytes)][ciphertext...][tag(16 bytes)]`.

5. **Metadata Encryption**
   - Original filename, timestamps, geolocation, MIME type and other metadata are fully encrypted.
   - Encrypted with the same CEK as the file.
   - Uploaded separately as a `.meta` file so the server cannot index or search metadata.

### Privacy Protection 🕵️
- Remote filenames are derived from file content using HMAC-SHA256 (with a salt derived from the master key), revealing no information.
- No plaintext hashes are uploaded, preventing the server from recognizing file content.
- Each device has its own device ID (UUID) that is not linked to user identity.
- All metadata, including EXIF geolocation, is fully encrypted.

## Usage Guide 📖

### Initial Setup 🚀
1. **First launch**: The app guides you through the initialization flow.
2. **Create account**:
   - Set a password (at least 12 characters, including upper/lowercase letters and digits).
   - The system generates a 12-word BIP39 mnemonic.
   - **Important**: Store the mnemonic safely, preferably offline (paper or password manager).
3. **Recover account**: If you already have a mnemonic, choose “Restore with mnemonic”.
4. **Grant permissions**: Grant photo and media access.

### Configure WebDAV 🌐
1. Go to the settings screen.
2. Enter your WebDAV server address (e.g. `https://cloud.example.com/remote.php/webdav`).
3. Enter username and password.
4. Tap **“Test connection”** to verify configuration.
5. Save the settings.

### Sync Settings ⚙️
- **Auto sync**: When enabled, backups run periodically.
- **Sync interval**: Configurable from 1 to 24 hours (default 12 hours).
- **Wi‑Fi only**: Sync only on Wi‑Fi to save mobile data (enabled by default).
- **Charging only**: Sync only while charging to save battery (disabled by default).
- **Monitored formats**: Configurable file extensions (default: `jpg, jpeg, png, gif, webp, heic, heif, mp4, mkv, avi, mov, 3gp`).

### Manual Sync ▶️
Tap the sync button on the main screen to start sync immediately. During sync you can:
- View real-time progress and which file is currently uploading.
- Pause / resume sync.
- Cancel sync.

### Upload Pipeline 📦
Each file upload includes the following steps:
1. **Compute hash**: Calculate SHA‑256 hash of the file.
2. **Encrypt file**: Encrypt the file using AES‑256‑GCM.
3. **Generate path**: Generate the remote path based on file content.
4. **Upload file**: Upload the encrypted file (large files are chunked automatically).
5. **Upload metadata**: Upload the encrypted metadata file.

## Recovery & Multi‑Device Use 📱💾

### Export Mnemonic
1. In settings, choose **“Export mnemonic”** (requires app to be unlocked).
2. Store the 12-word mnemonic safely (offline, e.g. paper or password manager).
3. **Warning**: If you lose both mnemonic and password, your data cannot be recovered.

### Restore on a New Device
1. After installation, choose **“Restore with mnemonic”**.
2. Enter the 12-word mnemonic and the original password.
3. Configure the same WebDAV server.
4. The app will sync all encrypted photos and metadata.

### Change Password
1. In settings, choose **“Change password”**.
2. Enter old password and new password.
3. The system re-wraps the master key; files do **not** need to be re-encrypted.

## Limitations & Notes ⚠️

### Current Limitations
- WebDAV chunked upload requires the server to support `PUT` with partial/chunked uploads.
- Large files (>100MB) and videos may take a long time to upload; it is recommended to sync on Wi‑Fi while charging.
- Conflict resolution is simplified: all versions are kept and distinguished by device ID.
- The app auto-locks 5 minutes after going to background; you must re-enter the password to unlock.

### Security Recommendations 🛡️
- Use a strong password (password manager recommended, at least 12 characters).
- **You must** keep your mnemonic safe (preferably offline, such as paper or a password manager).
- Use a trusted WebDAV server (self-hosted Nextcloud/ownCloud recommended).
- Enable TLS (HTTPS) on your WebDAV server.
- Check sync status regularly to ensure important photos are backed up.

### Performance Tips ⚡
- For the first sync, run it on Wi‑Fi while charging.
- A large number of photos (>1000) may take several hours; please be patient.
- The app automatically adjusts concurrency based on available memory; no manual tuning is required.
- Periodically clear failed sync records (you can reset upload history in settings).

### Supported Media Formats 🖼️🎬
- **Images**: JPG, JPEG, PNG, GIF, WEBP, HEIC, HEIF.
- **Videos**: MP4, MKV, AVI, MOV, 3GP.
- You can customize monitored file extensions in settings.

## Development & Contributions 🤝

### Build Requirements 🛠️
- Android Studio Hedgehog (2023.1.1) or higher.
- JDK 11 or higher.
- Android SDK 36 (`compileSdk`).
- Minimum supported Android 12 (API 31).
- Target SDK 33.

### Technical Debt / Future Work 📌
- [ ] Support more cloud storage protocols (S3, custom APIs).
- [x] Add end-to-end-encrypted photo viewer.
- [ ] Implement Argon2id key derivation (performance improvement, replacement for PBKDF2).
- [x] I18N.

## License 📜
GNU GENERAL PUBLIC LICENSE VERSION 3

## Disclaimer ⚠️

This app is in a **proof-of-concept** stage and is intended to demonstrate a zero-knowledge encrypted photo backup solution. Before production use, you need:
1. A complete security audit.
2. More robust error handling.
3. A database migration strategy.
4. Comprehensive unit and integration tests.
5. Performance optimization and stress testing.

Do **not** rely solely on this app for critical data. Always keep local backups. 💾
