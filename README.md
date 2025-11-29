# PicKeep - 零知识加密照片备份

PicKeep 是一个 Android 应用，提供零知识加密的照片备份服务。所有照片在本地进行端到端加密，服务器无法读取您的照片内容。

## 核心特性

### 零知识加密
- **端到端加密**：照片和元数据在上传前进行 AES-256-GCM 加密
- **密钥派生**：使用 PBKDF2-HMAC-SHA256 从用户密码派生主密钥
- **独立密钥**：每个文件使用独立的内容加密密钥（CEK）
- **隐私保护**：文件名使用随机 UUID，不泄露时间信息

### 增量同步
- **智能检测**：通过 MediaStore 和 FileObserver 监控照片变化
- **断点续传**：支持大文件分片上传和断点续传
- **批量处理**：并发上传控制和智能重试机制
- **状态管理**：完整的同步状态追踪和错误处理

### WebDAV 支持
- **标准协议**：兼容 Nextcloud、ownCloud 等 WebDAV 服务
- **TLS 加密**：传输层使用 HTTPS 保护
- **灵活配置**：支持自定义服务器地址和认证

### 后台任务
- **WorkManager**：使用 Android WorkManager 进行后台同步
- **智能调度**：可配置同步间隔、网络条件、充电状态
- **前台服务**：同步时显示进度通知

## 技术架构

### 依赖库
- **Jetpack Compose**：现代化的声明式 UI
- **Room**：本地数据库，存储同步状态
- **WorkManager**：后台任务调度
- **OkHttp**：HTTP 客户端，实现 WebDAV 协议
- **Kotlinx Serialization**：JSON 序列化
- **EncryptedSharedPreferences**：安全存储敏感配置
- **Bouncy Castle**：加密库支持

### 项目结构
```
app/src/main/java/net/kagamir/pickeep/
├── crypto/                  # 加密层
│   ├── KeyDerivation.kt    # 密钥派生
│   ├── CekManager.kt       # CEK 管理
│   ├── FileEncryptor.kt    # 文件加密
│   ├── MetadataEncryptor.kt # 元数据加密
│   └── MasterKeyStore.kt   # 主密钥管理
├── data/
│   ├── local/              # 本地数据
│   │   ├── entity/        # 数据实体
│   │   ├── dao/           # 数据访问对象
│   │   └── PicKeepDatabase.kt
│   └── repository/         # 数据仓库
├── storage/                # 存储层
│   └── webdav/            # WebDAV 实现
├── monitor/                # 监控层
│   ├── MediaStoreObserver.kt
│   ├── FileSystemObserver.kt
│   └── PhotoScanner.kt
├── sync/                   # 同步引擎
│   ├── SyncEngine.kt
│   ├── UploadTask.kt
│   └── SyncState.kt
├── worker/                 # 后台任务
│   ├── PhotoSyncWorker.kt
│   └── WorkManagerScheduler.kt
└── ui/                     # UI 层
    ├── screen/            # 界面
    ├── navigation/        # 导航
    └── theme/             # 主题
```

## 安全设计

### 加密方案
1. **主密钥（Master Key）**
   - 从用户密码通过 PBKDF2 派生（100,000 次迭代）
   - 仅存储在内存中，应用锁定时清除
   - 支持导出恢复码用于多设备

2. **内容加密密钥（CEK）**
   - 每个文件独立生成 256-bit 随机密钥
   - 用主密钥加密后存储在本地数据库
   - 支持密钥轮换和撤销

3. **文件加密**
   - 算法：AES-256-GCM
   - 模式：流式加密（64KB 分块）
   - 认证：包含认证标签，防止篡改

4. **元数据加密**
   - 原始文件名、时间戳、位置等信息全部加密
   - 使用与文件相同的 CEK
   - 服务器无法索引或搜索

### 隐私保护
- 远程文件名使用 UUID，不泄露任何信息
- 不上传明文哈希，防止服务器识别文件内容
- 每个设备独立的设备 ID，但不关联用户身份

## 使用指南

### 初始设置
1. 首次启动创建密码（至少 12 位，包含大小写字母和数字）
2. 保存恢复码（用于多设备或密钥恢复）
3. 授予照片访问权限

### 配置 WebDAV
1. 进入设置界面
2. 输入 WebDAV 服务器地址（例如：`https://cloud.example.com/remote.php/webdav`）
3. 输入用户名和密码
4. 点击"测试连接"验证配置
5. 保存设置

### 同步设置
- **自动同步**：启用后定期自动备份
- **同步间隔**：1-24 小时可选
- **WiFi 限制**：仅在 WiFi 下同步，节省流量
- **充电限制**：仅在充电时同步，节省电量

### 手动同步
点击主界面的同步按钮立即开始同步

## 恢复与多设备

### 导出恢复码
1. 在设置中选择"导出恢复码"
2. 妥善保存恢复码（建议离线保存）

### 在新设备上恢复
1. 安装应用后选择"导入恢复码"
2. 输入恢复码和原密码
3. 配置相同的 WebDAV 服务器
4. 应用将同步所有加密照片

## 限制与注意事项

### 当前版本限制
- WebDAV 分片上传依赖服务器支持
- 大文件（>100MB）可能需要较长时间
- 冲突解决采用简化策略（保留所有版本）

### 安全建议
- 使用强密码（推荐密码管理器生成）
- 定期备份恢复码
- 使用可信的 WebDAV 服务器（建议自建）
- 启用 WebDAV 服务器的 TLS 加密

### 性能建议
- 首次同步建议在 WiFi 和充电状态下进行
- 大量照片（>1000 张）可能需要数小时
- 定期清理失败的同步记录

## 开发与贡献

### 构建要求
- Android Studio Hedgehog (2023.1.1) 或更高
- JDK 11 或更高
- Android SDK 35
- 最低支持 Android 12 (API 31)

### 构建步骤
```bash
git clone <repository-url>
cd PicKeep
./gradlew assembleDebug
```

### 技术债务（未来改进）
- [ ] 支持更多云存储协议（S3、自定义 API）
- [ ] 实现真正的分片上传和断点续传
- [ ] 改进冲突解决 UI
- [ ] 支持照片选择性同步
- [ ] 添加端到端加密的照片查看器
- [ ] 实现 Argon2id 密钥派生（性能优化）
- [ ] 支持 BIP39 助记词

## 许可证

待定

## 免责声明

本应用处于技术验证阶段，仅用于演示零知识加密的照片备份方案。生产环境使用前需要：
1. 完整的安全审计
2. 更完善的错误处理
3. 数据库迁移策略
4. 完整的单元测试和集成测试
5. 性能优化和压力测试

请勿将重要数据完全依赖于本应用，建议保留本地备份。

