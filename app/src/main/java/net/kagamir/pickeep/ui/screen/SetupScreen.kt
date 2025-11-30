package net.kagamir.pickeep.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.kagamir.pickeep.crypto.KeyDerivation
import net.kagamir.pickeep.data.repository.SettingsRepository

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
                title = { Text("初始设置") },
                navigationIcon = {
                    if (setupMode != null) {
                        IconButton(onClick = { setupMode = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
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
                        text = "欢迎使用 PicKeep",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "零知识加密的照片备份",
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
                        Text("创建新账户")
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedButton(
                        onClick = { setupMode = SetupMode.RESTORE },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text("使用助记词恢复")
                    }
                }
            } else {
                // 具体流程
                when (setupMode) {
                    SetupMode.NEW -> NewAccountFlow(settingsRepository, onSetupComplete)
                    SetupMode.RESTORE -> RestoreAccountFlow(settingsRepository, onSetupComplete)
                    null -> {} // Unreachable
                }
            }
        }
    }
}

enum class SetupMode {
    NEW, RESTORE
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
                text = "备份助记词",
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "请按顺序抄写以下 12 个单词。如果丢失密码或设备，这是恢复数据的唯一方式。",
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
                Text("我已备份")
            }
            
        } else {
            // 设置密码
            Text(
                text = "设置主密码",
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text("密码") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; errorMessage = null },
                label = { Text("确认密码") },
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
                        errorMessage = "两次密码输入不一致"
                        return@Button
                    }
                    if (password.isEmpty()) {
                        errorMessage = "密码不能为空"
                        return@Button
                    }
                    
                    isLoading = true
                    try {
                        settingsRepository.initialize(password, mnemonicList)
                        onSetupComplete()
                    } catch (e: Exception) {
                        errorMessage = e.message ?: "初始化失败"
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
                    Text("完成设置")
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
                text = "恢复账户",
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = mnemonicInput,
                onValueChange = { mnemonicInput = it; errorMessage = null },
                label = { Text("输入助记词 (空格分隔)") },
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
                        errorMessage = "助记词长度无效 (当前: ${words.size}, 应为 12/15/18/21/24)"
                        return@Button
                    }
                    // 简单验证（实际应用应校验 Checksum）
                    step = RestoreAccountStep.SET_PASSWORD
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = mnemonicInput.isNotBlank()
            ) {
                Text("下一步")
            }
            
        } else {
            // 设置新密码
            Text(
                text = "设置新密码",
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text("新密码") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; errorMessage = null },
                label = { Text("确认密码") },
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
                        errorMessage = "两次密码输入不一致"
                        return@Button
                    }
                    if (password.isEmpty()) {
                        errorMessage = "密码不能为空"
                        return@Button
                    }
                    
                    isLoading = true
                    try {
                        val words = mnemonicInput.trim().split("\\s+".toRegex())
                        settingsRepository.initialize(password, words)
                        onSetupComplete()
                    } catch (e: Exception) {
                        errorMessage = e.message ?: "恢复失败，请检查助记词是否正确"
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
                    Text("恢复账户")
                }
            }
        }
    }
}

enum class RestoreAccountStep {
    ENTER_MNEMONIC, SET_PASSWORD
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
