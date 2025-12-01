package net.kagamir.pickeep.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import net.kagamir.pickeep.R
import net.kagamir.pickeep.crypto.KeyDerivation
import net.kagamir.pickeep.data.repository.SettingsRepository
import net.kagamir.pickeep.util.QrCodeHelper

/**
 * 初始设置界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    settingsRepository: SettingsRepository,
    onSetupComplete: () -> Unit
) {
    var setupMode by remember { mutableStateOf<SetupMode?>(null) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.title_initial_setup)) },
                navigationIcon = {
                    if (setupMode != null) {
                        IconButton(onClick = { setupMode = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (setupMode == null) {
                // 模式选择
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.title_welcome),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = stringResource(R.string.subtitle_zero_knowledge),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(48.dp))
                    
                    Button(
                        onClick = { setupMode = SetupMode.NEW },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(text = stringResource(R.string.btn_create_account))
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedButton(
                        onClick = { setupMode = SetupMode.RESTORE },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(text = stringResource(R.string.btn_recover_with_mnemonic))
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedButton(
                        onClick = { setupMode = SetupMode.RESTORE_QR },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Icon(Icons.Default.Camera, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.btn_recover_with_qr))
                    }
                }
            } else {
                // 具体流程
                when (setupMode) {
                    SetupMode.NEW -> NewAccountFlow(settingsRepository, onSetupComplete)
                    SetupMode.RESTORE -> RestoreAccountFlow(settingsRepository, onSetupComplete)
                    SetupMode.RESTORE_QR -> RestoreAccountQrFlow(settingsRepository, onSetupComplete)
                    null -> {} // Unreachable
                }
            }
        }
    }
}

enum class SetupMode {
    NEW, RESTORE, RESTORE_QR
}

@Composable
fun NewAccountFlow(
    settingsRepository: SettingsRepository,
    onSetupComplete: () -> Unit
) {
    var step by remember { mutableStateOf(NewAccountStep.SHOW_MNEMONIC) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    // 生成临时助记词（仅显示，实际初始化时会重新生成或传入）
    // 为了简化，我们在 SettingsRepository.initialize 时生成并返回。
    // 但是我们需要先显示给用户，用户确认后再设置密码？
    // 或者：生成 -> 显示 -> 设置密码 -> Initialize(password, generatedMnemonic)
    // 是的，我们需要在这里生成助记词。
    val mnemonicList = remember { net.kagamir.pickeep.crypto.KeyDerivation.generateMnemonic() }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (step == NewAccountStep.SHOW_MNEMONIC) {
            Text(
                text = stringResource(R.string.title_backup_mnemonic),
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = stringResource(R.string.msg_backup_mnemonic),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            MnemonicDisplay(mnemonicList)
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = { step = NewAccountStep.SET_PASSWORD },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.btn_mnemonic_backed_up))
            }
            
        } else {
            // 设置密码
            Text(
                text = stringResource(R.string.title_set_master_password),
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text(stringResource(R.string.label_password)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; errorMessage = null },
                label = { Text(stringResource(R.string.label_confirm_password)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = {
                    if (password != confirmPassword) {
                        errorMessage = context.getString(R.string.error_password_mismatch)
                        return@Button
                    }
                    if (password.isEmpty()) {
                        errorMessage = context.getString(R.string.error_password_empty)
                        return@Button
                    }
                    
                    isLoading = true
                    try {
                        settingsRepository.initialize(password, mnemonicList)
                        onSetupComplete()
                    } catch (e: Exception) {
                        errorMessage = e.message ?: context.getString(R.string.error_initialize_failed)
                    } finally {
                        isLoading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(text = stringResource(R.string.btn_finish_setup))
                }
            }
        }
    }
}

enum class NewAccountStep {
    SHOW_MNEMONIC, SET_PASSWORD
}

@Composable
fun RestoreAccountFlow(
    settingsRepository: SettingsRepository,
    onSetupComplete: () -> Unit
) {
    var step by remember { mutableStateOf(RestoreAccountStep.ENTER_MNEMONIC) }
    var mnemonicInput by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (step == RestoreAccountStep.ENTER_MNEMONIC) {
            Text(
                text = stringResource(R.string.title_recover_account),
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = mnemonicInput,
                onValueChange = { mnemonicInput = it; errorMessage = null },
                label = { Text(stringResource(R.string.label_input_mnemonic)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                maxLines = 5
            )
            
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = {
                    val words = mnemonicInput.trim().split("\\s+".toRegex())
                    if (words.size !in listOf(12, 15, 18, 21, 24)) {
                        errorMessage = context.getString(R.string.error_invalid_mnemonic_length, words.size)
                        return@Button
                    }
                    // 简单验证（实际应用应校验 Checksum）
                    step = RestoreAccountStep.SET_PASSWORD
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = mnemonicInput.isNotBlank()
            ) {
                Text(text = stringResource(R.string.btn_next))
            }
            
        } else {
            // 设置新密码
            Text(
                text = stringResource(R.string.title_set_new_password),
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text(stringResource(R.string.label_password)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; errorMessage = null },
                label = { Text(stringResource(R.string.label_confirm_password)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = {
                    if (password != confirmPassword) {
                        errorMessage = context.getString(R.string.error_password_mismatch)
                        return@Button
                    }
                    if (password.isEmpty()) {
                        errorMessage = context.getString(R.string.error_password_empty)
                        return@Button
                    }
                    
                    isLoading = true
                    try {
                        val words = mnemonicInput.trim().split("\\s+".toRegex())
                        settingsRepository.initialize(password, words)
                        onSetupComplete()
                    } catch (e: Exception) {
                        errorMessage = e.message ?: context.getString(R.string.error_recover_failed_check_mnemonic)
                    } finally {
                        isLoading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(text = stringResource(R.string.title_recover_account))
                }
            }
        }
    }
}

enum class RestoreAccountStep {
    ENTER_MNEMONIC, SET_PASSWORD
}

@Composable
fun RestoreAccountQrFlow(
    settingsRepository: SettingsRepository,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(RestoreAccountStep.ENTER_MNEMONIC) }
    var mnemonicInput by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    
    // 检查相机权限
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    // 二维码扫描启动器
    val qrCodeLauncher = rememberLauncherForActivityResult(
        contract = ScanContract()
    ) { result ->
        if (result.contents != null) {
            val mnemonic = QrCodeHelper.parseRecoveryQrData(result.contents)
            if (mnemonic != null) {
                mnemonicInput = mnemonic.joinToString(" ")
                errorMessage = null
            } else {
                errorMessage = context.getString(R.string.error_invalid_qr_format)
            }
        } else {
            // 扫描取消，不显示错误
        }
    }
    
    // 启动扫描的函数
    fun startScan() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt(context.getString(R.string.hint_qr_align))
        // 使用自定义竖屏扫码 Activity
        options.setCaptureActivity(net.kagamir.pickeep.ui.qr.QrCaptureActivity::class.java)
        options.setCameraId(0)
        options.setBeepEnabled(false)
        options.setBarcodeImageEnabled(false)
        qrCodeLauncher.launch(options)
    }
    
    // 相机权限请求启动器
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            // 权限已授予，启动扫描
            startScan()
        } else {
            errorMessage = context.getString(R.string.error_camera_permission_required)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (step == RestoreAccountStep.ENTER_MNEMONIC) {
            Text(
                text = stringResource(R.string.title_scan_recover_qr),
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = stringResource(R.string.msg_scan_recover_qr),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = {
                    if (!hasCameraPermission) {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    } else {
                        startScan()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Camera, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.btn_scan_qr))
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            HorizontalDivider()
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = stringResource(R.string.title_or_input_mnemonic),
                style = MaterialTheme.typography.titleMedium
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = mnemonicInput,
                onValueChange = { mnemonicInput = it; errorMessage = null },
                label = { Text(stringResource(R.string.label_input_mnemonic)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                maxLines = 5
            )
            
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = {
                    val words = mnemonicInput.trim().split("\\s+".toRegex())
                    if (words.size !in listOf(12, 15, 18, 21, 24)) {
                        errorMessage = context.getString(R.string.error_invalid_mnemonic_length, words.size)
                        return@Button
                    }
                    if (words.isEmpty()) {
                        errorMessage = context.getString(R.string.error_mnemonic_or_qr_required)
                        return@Button
                    }
                    // 简单验证（实际应用应校验 Checksum）
                    step = RestoreAccountStep.SET_PASSWORD
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = mnemonicInput.isNotBlank()
            ) {
                Text(text = stringResource(R.string.btn_next))
            }
            
        } else {
            // 设置新密码
            Text(
                text = stringResource(R.string.title_set_new_password),
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text(stringResource(R.string.label_password)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; errorMessage = null },
                label = { Text(stringResource(R.string.label_confirm_password)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = {
                    if (password != confirmPassword) {
                        errorMessage = context.getString(R.string.error_password_mismatch)
                        return@Button
                    }
                    if (password.isEmpty()) {
                        errorMessage = context.getString(R.string.error_password_empty)
                        return@Button
                    }
                    
                    isLoading = true
                    try {
                        val words = mnemonicInput.trim().split("\\s+".toRegex())
                        settingsRepository.initialize(password, words)
                        onSetupComplete()
                    } catch (e: Exception) {
                        errorMessage = e.message ?: context.getString(R.string.error_recover_failed_check_mnemonic)
                    } finally {
                        isLoading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(text = stringResource(R.string.title_recover_account))
                }
            }
        }
    }
}

@Composable
fun MnemonicDisplay(words: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .padding(16.dp)
    ) {
        val rows = words.chunked(3)
        rows.forEachIndexed { rowIndex, rowWords ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                rowWords.forEachIndexed { colIndex, word ->
                    val index = rowIndex * 3 + colIndex + 1
                    Text(
                        text = "$index. $word",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (rowIndex < rows.size - 1) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
