package com.example.ui.screens

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.Vehicle
import com.example.data.repository.DatabaseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.usermodel.DataFormatter
import java.io.InputStream
import java.io.OutputStream

class ImportDataViewModel(private val repository: DatabaseRepository) : ViewModel() {
    var statusMessage by mutableStateOf("")
    var isImporting by mutableStateOf(false)
    var uploadedFiles by mutableStateOf<List<com.example.data.repository.UploadedFileMeta>>(emptyList())

    val adminMobile: String
        get() = com.example.logic.AuthManager.currentUser.value?.mobile ?: "admin"

    fun loadUploadedFiles(context: android.content.Context) {
        viewModelScope.launch {
            try {
                if (com.example.logic.NetworkUtils.isNetworkAvailable(context)) {
                    uploadedFiles = repository.getFirestoreUploadedFiles(adminMobile)
                } else {
                    uploadedFiles = repository.getLocalUploadedFiles(adminMobile, context)
                }
            } catch (e: Exception) {
                Log.e("ImportData", "Error getting uploaded files", e)
                try {
                    uploadedFiles = repository.getLocalUploadedFiles(adminMobile, context)
                } catch (inner: Exception) {
                    uploadedFiles = emptyList()
                }
            }
        }
    }

    fun deleteFileMetadataAndItsVehicles(meta: com.example.data.repository.UploadedFileMeta, context: android.content.Context) {
        viewModelScope.launch {
            isImporting = true
            statusMessage = "Deleting file '${meta.fileName}' and its vehicle data..."
            try {
                val isOnline = com.example.logic.NetworkUtils.isNetworkAvailable(context)
                
                // Delete local records first (always!)
                withContext(Dispatchers.IO) {
                    repository.deleteLocalFileAndItsData(meta.adminMobile, meta.fileName)
                }

                // Remove localized timestamp
                val prefs = context.getSharedPreferences("recoveryx_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().remove("file_time_${meta.adminMobile}_${meta.fileName}").apply()

                if (isOnline) {
                    try {
                        withContext(Dispatchers.IO) {
                            repository.deleteCloudFileAndItsData(meta.adminMobile, meta.fileName)
                        }
                        statusMessage = "Successfully deleted file and all its records globally and locally."
                    } catch (e: Exception) {
                        Log.e("ImportData", "Error deleting cloud data", e)
                        statusMessage = "Successfully deleted file and all its records locally. (Cloud sync pending)"
                    }
                } else {
                    statusMessage = "Successfully deleted file and all its records locally from this device."
                }
                loadUploadedFiles(context)
            } catch (e: Exception) {
                statusMessage = "Error deleting: ${e.localizedMessage}"
            } finally {
                isImporting = false
            }
        }
    }

    private fun getFileNameFromUri(context: android.content.Context, uri: Uri): String {
        var name = "unknown_file_${System.currentTimeMillis()}"
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }

    fun importPickedFile(context: android.content.Context, uri: Uri) {
        viewModelScope.launch {
            isImporting = true
            statusMessage = "Resolving file..."
            try {
                val fileName = getFileNameFromUri(context, uri)
                val activeAdminMobile = adminMobile
                val isOnline = com.example.logic.NetworkUtils.isNetworkAvailable(context)
                
                // 1. Verify uniqueness
                statusMessage = "Checking uniqueness of file name..."
                var isAlreadyUploaded = false
                if (isOnline) {
                    try {
                        isAlreadyUploaded = repository.isFirestoreFileUploaded(activeAdminMobile, fileName)
                    } catch (e: Exception) {
                        isAlreadyUploaded = repository.countVehiclesByFile(activeAdminMobile, fileName) > 0
                    }
                } else {
                    isAlreadyUploaded = repository.countVehiclesByFile(activeAdminMobile, fileName) > 0
                }
                
                if (isAlreadyUploaded) {
                    statusMessage = "Error: File name '$fileName' already exists. Please delete the existing file or rename your file before upload."
                    isImporting = false
                    return@launch
                }

                statusMessage = "Parsing file elements..."
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    statusMessage = "Error: Could not open the selected file stream."
                    isImporting = false
                    return@launch
                }

                val vehicles = withContext(Dispatchers.IO) {
                    if (fileName.endsWith(".csv", ignoreCase = true)) {
                        parseCsvFile(inputStream, activeAdminMobile, fileName)
                    } else {
                        parseExcelFile(inputStream, activeAdminMobile, fileName)
                    }
                }

                if (vehicles.isEmpty()) {
                    statusMessage = "Error: No valid vehicle records found in the file."
                    isImporting = false
                    return@launch
                }

                // 2. Save records locally into Room database first (always!)
                statusMessage = "Saving ${vehicles.size} records locally..."
                withContext(Dispatchers.IO) {
                    vehicles.forEach { repository.insertVehicle(it) }
                }

                // Record local import timestamp in prefs
                val prefs = context.getSharedPreferences("recoveryx_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong("file_time_${activeAdminMobile}_${fileName}", System.currentTimeMillis())
                    .apply()

                // 3. Upload to Firestore if online
                if (isOnline) {
                    statusMessage = "Syncing records to Firestore database..."
                    try {
                        withContext(Dispatchers.IO) {
                            repository.uploadFileMetadataAndVehicles(activeAdminMobile, fileName, vehicles)
                        }
                        prefs.edit().putLong("last_download_time", System.currentTimeMillis()).apply()
                        statusMessage = "Successfully imported ${vehicles.size} records from '$fileName' globally and locally!"
                    } catch (e: Exception) {
                        Log.e("ImportData", "Error syncing to Firestore", e)
                        statusMessage = "Successfully imported ${vehicles.size} records locally! (Firestore sync pending once online)"
                    }
                } else {
                    statusMessage = "Successfully imported ${vehicles.size} records locally! (Device is offline - records will sync when online)"
                }

                loadUploadedFiles(context)
            } catch (e: Throwable) {
                Log.e("ImportData", "Error importing file", e)
                if (e is NoClassDefFoundError || e is LinkageError || e is BootstrapMethodError) {
                    statusMessage = "Error: Excel parsing not supported on this device. Please convert your file to .csv format and import!"
                } else {
                    statusMessage = "Error: ${e.localizedMessage ?: e.toString()}"
                }
            } finally {
                isImporting = false
            }
        }
    }

    private fun parseCsvFile(inputStream: InputStream, adminMobile: String, fileName: String): List<Vehicle> {
        val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))
        val headerLine = reader.readLine() ?: return emptyList()
        val headers = headerLine.split(",").map { it.trim().lowercase() }
        
        var customerIndex = 0
        var vehicleNumberIndex = 1
        var bankNameIndex = 2
        var posIndex = 3
        var emiIndex = 4
        var engineNumberIndex = 5
        var chassisNumberIndex = 6
        var confirmerNameIndex = 7
        var modelIndex = -1
        var loanNoIndex = -1
        var statusIndex = -1

        headers.forEachIndexed { c, colValue ->
            when {
                colValue.contains("owner") || colValue.contains("customer") -> customerIndex = c
                colValue.contains("vehicle") || colValue.contains("registration") || colValue.contains("reg") || colValue.contains("veh") -> vehicleNumberIndex = c
                colValue.contains("bank") -> bankNameIndex = c
                colValue.contains("pos") || colValue.contains("point") -> posIndex = c
                colValue.contains("emi") -> emiIndex = c
                colValue.contains("engine") -> engineNumberIndex = c
                colValue.contains("chassis") -> chassisNumberIndex = c
                colValue.contains("confirmer") || colValue.contains("agent") -> confirmerNameIndex = c
                colValue.contains("model") || colValue.contains("make") -> modelIndex = c
                colValue.contains("loan") -> loanNoIndex = c
                colValue.contains("status") -> statusIndex = c
            }
        }

        val vehicles = mutableListOf<Vehicle>()
        var line = reader.readLine()
        while (line != null) {
            val columns = parseCsvLine(line)
            if (columns.size > vehicleNumberIndex) {
                val customerName = columns.getOrNull(customerIndex) ?: ""
                val vehicleNumber = (columns.getOrNull(vehicleNumberIndex) ?: "").replace("\\s+".toRegex(), "")
                val bankName = columns.getOrNull(bankNameIndex) ?: ""
                val pos = columns.getOrNull(posIndex) ?: ""
                val emi = columns.getOrNull(emiIndex) ?: ""
                val engineNumber = columns.getOrNull(engineNumberIndex) ?: ""
                val chassisNumber = columns.getOrNull(chassisNumberIndex) ?: ""
                val confirmerName = columns.getOrNull(confirmerNameIndex) ?: ""
                val model = if (modelIndex != -1) columns.getOrNull(modelIndex) ?: "" else ""
                val loanNo = if (loanNoIndex != -1) columns.getOrNull(loanNoIndex) ?: "" else ""
                val statusValue = if (statusIndex != -1) columns.getOrNull(statusIndex) ?: "Active" else "Active"
                val status = if (statusValue.trim().isEmpty()) "Active" else statusValue.trim()

                if (vehicleNumber.isNotEmpty()) {
                    vehicles.add(
                        Vehicle(
                            customerName = customerName,
                            vehicleNumber = vehicleNumber,
                            model = model,
                            status = status,
                            bankName = bankName,
                            pos = pos,
                            emi = emi,
                            engineNumber = engineNumber,
                            chassisNumber = chassisNumber,
                            confirmerName = confirmerName,
                            loanNo = loanNo,
                            creatorMobile = adminMobile,
                            fileName = fileName
                        )
                    )
                }
            }
            line = reader.readLine()
        }
        return vehicles
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            if (ch == '\"') {
                inQuotes = !inQuotes
            } else if (ch == ',' && !inQuotes) {
                result.add(current.toString().trim())
                current = StringBuilder()
            } else {
                current.append(ch)
            }
        }
        result.add(current.toString().trim())
        return result
    }

    private fun parseExcelFile(inputStream: java.io.InputStream, adminMobile: String, fileName: String): List<Vehicle> {
        val vehicles = mutableListOf<Vehicle>()
        val workbook = WorkbookFactory.create(inputStream)
        val sheet = workbook.getSheetAt(0)
        val formatter = DataFormatter()
        
        val headerRow = sheet.getRow(0)
        var customerIndex = 0
        var vehicleNumberIndex = 1
        var bankNameIndex = 2
        var posIndex = 3
        var emiIndex = 4
        var engineNumberIndex = 5
        var chassisNumberIndex = 6
        var confirmerNameIndex = 7
        var modelIndex = -1
        var statusIndex = -1
        var loanNoIndex = -1

        if (headerRow != null) {
            for (c in 0 until headerRow.lastCellNum.toInt()) {
                val cellValue = formatter.formatCellValue(headerRow.getCell(c)).trim().lowercase()
                when {
                    cellValue.contains("owner") || cellValue.contains("customer") -> customerIndex = c
                    cellValue.contains("vehicle") || cellValue.contains("registration") || cellValue.contains("reg") || cellValue.contains("veh") -> vehicleNumberIndex = c
                    cellValue.contains("bank") -> bankNameIndex = c
                    cellValue.contains("pos") || cellValue.contains("point") -> posIndex = c
                    cellValue.contains("emi") -> emiIndex = c
                    cellValue.contains("engine") -> engineNumberIndex = c
                    cellValue.contains("chassis") -> chassisNumberIndex = c
                    cellValue.contains("confirmer") || cellValue.contains("agent") -> confirmerNameIndex = c
                    cellValue.contains("model") || cellValue.contains("make") -> modelIndex = c
                    cellValue.contains("loan") -> loanNoIndex = c
                    cellValue.contains("status") -> statusIndex = c
                }
            }
        }

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            
            val customerName = formatter.formatCellValue(row.getCell(customerIndex))
            val vehicleNumber = formatter.formatCellValue(row.getCell(vehicleNumberIndex)).replace("\\s+".toRegex(), "")
            val bankName = formatter.formatCellValue(row.getCell(bankNameIndex))
            val pos = formatter.formatCellValue(row.getCell(posIndex))
            val emi = formatter.formatCellValue(row.getCell(emiIndex))
            val engineNumber = formatter.formatCellValue(row.getCell(engineNumberIndex))
            val chassisNumber = formatter.formatCellValue(row.getCell(chassisNumberIndex))
            val confirmerName = formatter.formatCellValue(row.getCell(confirmerNameIndex))
            val model = if (modelIndex != -1) formatter.formatCellValue(row.getCell(modelIndex)) else ""
            val loanNo = if (loanNoIndex != -1) formatter.formatCellValue(row.getCell(loanNoIndex)) else ""
            val statusValue = if (statusIndex != -1) formatter.formatCellValue(row.getCell(statusIndex)) else "Active"
            val status = if (statusValue.trim().isEmpty()) "Active" else statusValue.trim()

            if (vehicleNumber.isNotEmpty()) {
                vehicles.add(
                    Vehicle(
                        customerName = customerName,
                        vehicleNumber = vehicleNumber,
                        model = model,
                        status = status,
                        bankName = bankName,
                        pos = pos,
                        emi = emi,
                        engineNumber = engineNumber,
                        chassisNumber = chassisNumber,
                        confirmerName = confirmerName,
                        loanNo = loanNo,
                        creatorMobile = adminMobile,
                        fileName = fileName
                    )
                )
            }
        }
        workbook.close()
        return vehicles
    }

    private fun writeSampleWorkbook(outputStream: OutputStream) {
        val workbook = org.apache.poi.xssf.usermodel.XSSFWorkbook()
        val sheet = workbook.createSheet("Vehicles")
        val headerRow = sheet.createRow(0)
        val headers = listOf(
            "Customer Name", "Vehicle Number", "Bank Name", "POS", 
            "EMI", "Engine Number", "Chassis Number", "Confirmer Name", 
            "Model", "Status", "Loan No"
        )
        headers.forEachIndexed { index, header ->
            headerRow.createCell(index).setCellValue(header)
        }
        
        val sampleData = listOf(
            listOf("Rajesh Kumar", "RJ14GB8829", "HDFC Bank", "Jaipur", "7500", "ENG987234", "CHSRJ14G88", "Amit Sharma", "Maruti Swift", "Active", "LN-1002341"),
            listOf("Sunita Devi", "RJ20CB4120", "SBI", "Kota", "5400", "ENG382190", "CHSRJ20C41", "Priyanka Patel", "Hyundai i20", "Clear", "LN-8492019"),
            listOf("Anil Mehta", "RJ19BD7700", "ICICI Bank", "Jodhpur", "13500", "ENG726190", "CHSRJ19B77", "Rahul Verma", "Toyota Innova", "Hold", "LN-5758291")
        )
        
        sampleData.forEachIndexed { rowIndex, rowData ->
            val row = sheet.createRow(rowIndex + 1)
            rowData.forEachIndexed { colIndex, value ->
                row.createCell(colIndex).setCellValue(value)
            }
        }
        
        workbook.write(outputStream)
        workbook.close()
    }

    fun generateAndSaveSampleExcel(context: android.content.Context) {
        viewModelScope.launch {
            statusMessage = "Generating Excel file..."
            try {
                val success = withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val filename = "Sample_Vehicles_3.xlsx"
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                    }
                    
                    var outputStream: OutputStream? = null
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                            if (uri != null) {
                                outputStream = resolver.openOutputStream(uri)
                            }
                        } else {
                            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            if (!downloadDir.exists()) downloadDir.mkdirs()
                            val file = java.io.File(downloadDir, filename)
                            outputStream = java.io.FileOutputStream(file)
                        }
                        
                        if (outputStream != null) {
                            writeSampleWorkbook(outputStream)
                            true
                        } else false
                    } catch (e: Exception) {
                        false
                    } finally {
                        outputStream?.close()
                    }
                }
                
                if (success) {
                    statusMessage = "Successfully saved 'Sample_Vehicles_3.xlsx' to Downloads folder."
                } else {
                    statusMessage = "Error: Could not save sample."
                }
            } catch (e: Throwable) {
                if (e is NoClassDefFoundError || e is LinkageError || e is BootstrapMethodError) {
                    statusMessage = "Error: Excel creation not supported on this device."
                } else {
                    statusMessage = "Error: ${e.localizedMessage ?: e.toString()}"
                }
            }
        }
    }

    fun importSampleDataDirectly(context: android.content.Context) {
        viewModelScope.launch {
            isImporting = true
            statusMessage = "Uploading direct 3-item demo data..."
            try {
                val activeAdminMobile = adminMobile
                val fileName = "Instant_Demo_${System.currentTimeMillis() / 1000}.xlsx"
                val isOnline = com.example.logic.NetworkUtils.isNetworkAvailable(context)
                
                val sampleData = listOf(
                    Vehicle(customerName = "Rajesh Kumar", vehicleNumber = "RJ14GB8829", bankName = "HDFC Bank", pos = "Jaipur", emi = "7500", engineNumber = "ENG987234", chassisNumber = "CHSRJ14G88", confirmerName = "Amit Sharma", model = "Maruti Swift", status = "Active", loanNo = "LN-1002341", creatorMobile = activeAdminMobile, fileName = fileName),
                    Vehicle(customerName = "Sunita Devi", vehicleNumber = "RJ20CB4120", bankName = "SBI", pos = "Kota", emi = "5400", engineNumber = "ENG382190", chassisNumber = "CHSRJ20C41", confirmerName = "Priyanka Patel", model = "Hyundai i20", status = "Clear", loanNo = "LN-8492019", creatorMobile = activeAdminMobile, fileName = fileName),
                    Vehicle(customerName = "Anil Mehta", vehicleNumber = "RJ19BD7700", bankName = "ICICI Bank", pos = "Jodhpur", emi = "13500", engineNumber = "ENG726190", chassisNumber = "CHSRJ19B77", confirmerName = "Rahul Verma", model = "Toyota Innova", status = "Hold", loanNo = "LN-5758291", creatorMobile = activeAdminMobile, fileName = fileName)
                )

                // Save locally first
                withContext(Dispatchers.IO) {
                    sampleData.forEach { repository.insertVehicle(it) }
                }

                // Record local import timestamp
                val prefs = context.getSharedPreferences("recoveryx_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putLong("file_time_${activeAdminMobile}_${fileName}", System.currentTimeMillis()).apply()

                if (isOnline) {
                    try {
                        withContext(Dispatchers.IO) {
                            repository.uploadFileMetadataAndVehicles(activeAdminMobile, fileName, sampleData)
                        }
                        statusMessage = "Successfully generated and imported 3 sample records globally! Filename: $fileName"
                    } catch (e: Exception) {
                        Log.e("ImportData", "Error uploading demo data to cloud", e)
                        statusMessage = "Successfully generated and imported 3 sample records locally! (Cloud sync pending)"
                    }
                } else {
                    statusMessage = "Successfully generated and imported 3 sample records locally! (Device offline)"
                }

                loadUploadedFiles(context)
            } catch (e: Exception) {
                statusMessage = "Error: ${e.localizedMessage}"
            } finally {
                isImporting = false
            }
        }
    }

    class Factory(private val repository: DatabaseRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ImportDataViewModel(repository) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportDataScreen(repository: DatabaseRepository, onBack: () -> Unit) {
    val viewModel: ImportDataViewModel = viewModel(factory = ImportDataViewModel.Factory(repository))
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadUploadedFiles(context)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.importPickedFile(context, it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Excel / CSV Data Importer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Import section card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(54.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Upload Vehicle Database",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Supports Excel (.xlsx, .xls) and CSV (.csv) formats.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = { 
                            // CSV and Excel filters
                            filePickerLauncher.launch("*/*")
                        },
                        modifier = Modifier.fillMaxWidth(0.9f),
                        enabled = !viewModel.isImporting
                    ) {
                        if (viewModel.isImporting) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("Select File to Upload")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Option 1: Put Excel on Device
                        OutlinedButton(
                            onClick = { viewModel.generateAndSaveSampleExcel(context) },
                            modifier = Modifier.weight(1f),
                            enabled = !viewModel.isImporting
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sample Excel", style = MaterialTheme.typography.labelSmall)
                        }
                        
                        // Option 2: Instant direct import
                        Button(
                            onClick = { viewModel.importSampleDataDirectly(context) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            enabled = !viewModel.isImporting
                        ) {
                            Icon(Icons.Default.FlashOn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Instant Import", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (viewModel.statusMessage.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.small,
                    color = if (viewModel.statusMessage.startsWith("Error")) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = viewModel.statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                        color = if (viewModel.statusMessage.startsWith("Error")) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            // Uploaded Files Header
            Text(
                text = "Uploaded Database Files",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                color = MaterialTheme.colorScheme.primary
            )

            // Uploaded Files List
            if (viewModel.uploadedFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No uploaded database files found for your admin account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(viewModel.uploadedFiles) { fileMeta ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fileMeta.fileName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${fileMeta.recordCount} records",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = formatTimestamp(fileMeta.uploadedAt),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                
                                IconButton(
                                    onClick = { viewModel.deleteFileMetadataAndItsVehicles(fileMeta, context) },
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    enabled = !viewModel.isImporting
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete File"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val sdf = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault())
    return sdf.format(date)
}

