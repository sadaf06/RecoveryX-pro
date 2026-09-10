package com.example.ui.screens

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var showDuplicateDialog by mutableStateOf(false)
    var duplicateErrorFileName by mutableStateOf("")

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
                    duplicateErrorFileName = fileName
                    showDuplicateDialog = true
                    statusMessage = "Error: Duplicate file name '$fileName'"
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
        var bucketIndex = -1

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
                colValue.contains("bucket") || colValue.contains("category") || colValue.contains("group") -> bucketIndex = c
            }
        }

        val vehicles = mutableListOf<Vehicle>()
        var line = reader.readLine()
        while (line != null) {
            val columns = parseCsvLine(line)
            if (columns.size > vehicleNumberIndex) {
                val customerName = columns.getOrNull(customerIndex) ?: ""
                val vehicleNumber = (columns.getOrNull(vehicleNumberIndex) ?: "").replace("\\s+".toRegex(), "").uppercase()
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
                val bucket = if (bucketIndex != -1) columns.getOrNull(bucketIndex) ?: "" else ""

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
                            fileName = fileName,
                            bucket = bucket
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
        var bucketIndex = -1

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
                    cellValue.contains("bucket") || cellValue.contains("category") || cellValue.contains("group") -> bucketIndex = c
                }
            }
        }

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            
            val customerName = formatter.formatCellValue(row.getCell(customerIndex))
            val vehicleNumber = formatter.formatCellValue(row.getCell(vehicleNumberIndex)).replace("\\s+".toRegex(), "").uppercase()
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
            val bucket = if (bucketIndex != -1) formatter.formatCellValue(row.getCell(bucketIndex)) else ""

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
                        fileName = fileName,
                        bucket = bucket
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
            "Model", "Status", "Loan No", "Bucket"
        )
        headers.forEachIndexed { index, header ->
            headerRow.createCell(index).setCellValue(header)
        }
        
        val sampleData = listOf(
            listOf("Rajesh Kumar", "RJ14GB8829", "HDFC Bank", "Jaipur", "7500", "ENG987234", "CHSRJ14G88", "Amit Sharma", "Maruti Swift", "Active", "LN-1002341", "High Priority"),
            listOf("Sunita Devi", "RJ20CB4120", "SBI", "Kota", "5400", "ENG382190", "CHSRJ20C41", "Priyanka Patel", "Hyundai i20", "Clear", "LN-8492019", "Low Priority"),
            listOf("Anil Mehta", "RJ19BD7700", "ICICI Bank", "Jodhpur", "13500", "ENG726190", "CHSRJ19B77", "Rahul Verma", "Toyota Innova", "Hold", "LN-5758291", "Standard")
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

    if (viewModel.showDuplicateDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showDuplicateDialog = false },
            title = {
                Text(
                    text = "DUPLICATE FILE DETECTED",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
            },
            text = {
                Text(
                    text = "The file '${viewModel.duplicateErrorFileName}' has already been uploaded by you. Admins cannot upload files with duplicate names.\n\nPlease delete the existing database or rename your source file before attempting another upload.",
                    color = Color.LightGray
                )
            },
            containerColor = Color(0xFF131929),
            confirmButton = {
                Button(
                    onClick = { viewModel.showDuplicateDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5D73))
                ) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A0B10), Color(0xFF12131A))
                )
            )
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(colors = listOf(Color(0x334F7CFF), Color.Transparent)),
                    radius = size.width * 1.0f,
                    center = Offset(x = size.width * 0.1f, y = size.height * 0.1f)
                )
                drawCircle(
                    brush = Brush.radialGradient(colors = listOf(Color(0x1F7B61FF), Color.Transparent)),
                    radius = size.width * 0.9f,
                    center = Offset(x = size.width * 0.9f, y = size.height * 0.8f)
                )
            }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { 
                        Text(
                            text = "DATABASE INGESTION",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp,
                            color = Color.White
                        ) 
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0x3B070A13),
                        titleContentColor = Color.White
                    )
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
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x17FFFFFF)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Brush.linearGradient(colors = listOf(Color(0xFF4F7CFF), Color(0x05FFFFFF))))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(Color(0xFF4F7CFF).copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape)
                                .border(1.dp, Color(0xFF4FD1FF).copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.UploadFile,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = Color(0xFF4FD1FF)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "INITIALIZE UPLOAD SEQUENCE",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Compatible strictly with EXCEL (.xlsx, .xls) and CSV.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFA1A8B8),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Button(
                            onClick = { 
                                filePickerLauncher.launch("*/*")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F7CFF)),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            enabled = !viewModel.isImporting
                        ) {
                            if (viewModel.isImporting) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text("SELECT SOURCE FILE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.generateAndSaveSampleExcel(context) },
                                modifier = Modifier.weight(1f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                enabled = !viewModel.isImporting
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("SAMPLE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            
                            Button(
                                onClick = { viewModel.importSampleDataDirectly(context) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0x1F7B61FF), contentColor = Color(0xFF7B61FF)),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                enabled = !viewModel.isImporting
                            ) {
                                Icon(Icons.Default.FlashOn, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("DEMO", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (viewModel.statusMessage.isNotEmpty()) {
                    val isError = viewModel.statusMessage.startsWith("Error")
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = if (isError) Color(0xFFFF5D73).copy(alpha = 0.15f) else Color(0xFF4FD1FF).copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isError) Color(0xFFFF5D73).copy(alpha = 0.5f) else Color(0xFF4FD1FF).copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = viewModel.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                            color = if (isError) Color(0xFFFF8596) else Color(0xFF4FD1FF),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Text(
                    text = "ACTIVE DATABASES",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
                    color = Color(0xFF4FD1FF)
                )

                if (viewModel.uploadedFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "NO DATABASES FOUND",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFA1A8B8),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(viewModel.uploadedFiles) { fileMeta ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0x0AFFFFFF)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1AFFFFFF))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = fileMeta.fileName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0xFF7B61FF).copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "${fileMeta.recordCount} RECORDS",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFFB5A1FF),
                                                    fontWeight = FontWeight.Black
                                                )
                                            }
                                            Text(
                                                text = formatTimestamp(fileMeta.uploadedAt),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFFA1A8B8)
                                            )
                                        }
                                    }
                                    
                                    IconButton(
                                        onClick = { viewModel.deleteFileMetadataAndItsVehicles(fileMeta, context) },
                                        enabled = !viewModel.isImporting
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete File",
                                            tint = Color(0xFFFF5D73)
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
}

fun formatTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val sdf = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault())
    return sdf.format(date)
}

