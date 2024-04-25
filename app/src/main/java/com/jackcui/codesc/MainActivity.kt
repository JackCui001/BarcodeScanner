package com.jackcui.codesc

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.text.set
import androidx.preference.PreferenceManager
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.TypeReference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.hjq.toast.ToastParams
import com.hjq.toast.Toaster
import com.hjq.toast.style.CustomToastStyle
import com.huawei.hms.hmsscankit.ScanUtil
import com.huawei.hms.ml.scan.HmsScan
import com.huawei.hms.ml.scan.HmsScan.AddressInfo
import com.huawei.hms.ml.scan.HmsScan.TelPhoneNumber
import com.huawei.hms.ml.scan.HmsScan.WiFiConnectionInfo
import com.huawei.hms.ml.scan.HmsScanFrame
import com.huawei.hms.ml.scan.HmsScanFrameOptions
import com.jackcui.codesc.databinding.ActivityMainBinding
import com.jackcui.codesc.databinding.DialogHistoryPickerBinding
import java.io.File
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


// 定义历史记录类型：map<日期,list<内容>>
typealias History = MutableMap<String, MutableList<String>>

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var multiScanPrefix: String? = null
    private var multiPicPrefix: String? = null
    private var multiCodePrefix: String? = null
    private var saveImageUri: Uri? = null

    // 定义Activity result launcher
    private val takePhotoLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                saveImageUri?.let {
                    scanPic(MediaStore.Images.Media.getBitmap(contentResolver, it))
                }
            }
        }
    private val pickFilesLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) {
            if (it.size == 1) {
                scanPic(uri = it[0])
            } else {
                for (i in it.indices) {
                    scanPic(uri = it[i], multiPicIdx = i, multiPicAmt = it.size)
                }
            }
        }
    private var scanCnt = 1
    private var parse = true


    companion object {
        /**
         * Define requestCode.
         */
        const val HW_SCAN_REQ_CODE = 1
        const val TAG = "CodeScanner"
        const val WAIT_FOR_SCAN = "等待识别"
        const val INVOKED_BY_INTENT_VIEW = "【由外部应用打开文件调用】"
        const val INVOKED_BY_INTENT_SEND = "【由外部应用分享文件调用】"

        fun showSnackbar(view: View, msg: String, duration: Int) {
            Snackbar.make(view, msg, duration).show()
        }

        fun showInfoToast(text: String) {
            val params = ToastParams()
            params.text = text
            params.style = CustomToastStyle(R.layout.toast_info, Gravity.BOTTOM, 0, 64)
            Toaster.show(params)
        }

        fun showWarnToast(text: String) {
            val params = ToastParams()
            params.text = text
            params.style = CustomToastStyle(R.layout.toast_warn, Gravity.BOTTOM, 0, 64)
            Toaster.show(params)
        }

        fun showErrorToast(text: String) {
            val params = ToastParams()
            params.text = text
            params.style = CustomToastStyle(R.layout.toast_error, Gravity.BOTTOM, 0, 64)
            Toaster.show(params)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化 Toast 框架
        Toaster.init(this.application)

        // Init str
        binding.tvOutput.text =
            SpannableStringBuilder().appendMySpan(WAIT_FOR_SCAN, "#7400FF", null)

        // Get intent, action and MIME type
        val intent = intent
        val action = intent.action
        val type = intent.type
        Log.d(TAG, intent.toString())

        if (type != null) {
            if (!type.startsWith("image/")) {
                showSnackbar(binding.fabScanCode, "导入了错误的文件类型", Snackbar.LENGTH_LONG)
            } else if (action == Intent.ACTION_SEND) {
                binding.tvOutput.text = SpannableStringBuilder().appendMySpan(
                    "$INVOKED_BY_INTENT_SEND\n", "#FF4400", 1.1
                )
                handleSendImage(intent) // Handle single image being sent
            } else if (action == Intent.ACTION_SEND_MULTIPLE) {
                binding.tvOutput.text = SpannableStringBuilder().appendMySpan(
                    "$INVOKED_BY_INTENT_SEND\n", "#FF4400", 1.1
                )
                handleSendMultipleImages(intent) // Handle multiple images being sent
            } else if (action == Intent.ACTION_VIEW) {
                binding.tvOutput.text = SpannableStringBuilder().appendMySpan(
                    "$INVOKED_BY_INTENT_VIEW\n", "#FF4400", 1.1
                )
                handleViewImage(intent) // Handle single image being viewed
            }
        }

        binding.topAppBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.item_help -> {
                    MaterialAlertDialogBuilder(this).setTitle("帮助")
                        .setMessage("欢迎使用此应用\n本应用虽然名叫“扫码仪”，但实际上不仅提供了各类码的扫描识别功能，还提供了构建功能。\n支持外部应用导入图片扫描，在外部应用选择打开或分享图片并选择此应用即可。\n扫描码内容的同时可以解析提取各类有效信息，而不必纠结于原始字符串的含义。\n构建功能仍处于Beta阶段，有问题可以随时和开发者反馈。\n下列功能支持一图多码扫描：\n图片文件扫描，拍照扫描\n\n开发者：酷安@威尼斯的向日葵")
                        .show()
                    true
                }

                R.id.item_cfg -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }

                else -> false
            }
        }

        binding.fabClear.setOnClickListener {
            Log.d(TAG, "tvOutput Cleared")
            showSnackbar(binding.fabScanCode, "输出信息已清空", Snackbar.LENGTH_SHORT)
            binding.tvOutput.text =
                SpannableStringBuilder().appendMySpan(WAIT_FOR_SCAN, "#7400FF", null)
            scanCnt = 1
        }

        binding.fabScanCode.setOnClickListener {
            val items = arrayOf("普通扫码", "拍照扫码", "图片文件扫码")
            var choice = -1
            MaterialAlertDialogBuilder(this).setTitle("扫码方式")
                .setSingleChoiceItems(items, -1) { _, which ->
                    choice = which
                    val text = StringBuilder("${items[which]}：")
                    text.append(
                        when (which) {
                            0 -> "弹出取景框实时扫描，最主流的扫码方式，与微信、支付宝相同，只支持单码"
                            1 -> "进入相机拍照界面，按下拍照按钮后才对照片进行扫描，支持多码"
                            2 -> "进入文件管理器，选择图片文件扫描，可批量选择图片，支持多码"
                            else -> ""
                        }
                    )
                    showInfoToast(text.toString())
                }.setPositiveButton("确定") { _, _ ->
                    when (choice) {
                        0 -> reqPerm(true)
                        1 -> reqPerm(false)
                        2 -> pickFilesLauncher.launch("image/*")
                    }
                }.show()
        }

        binding.fabGenCode.setOnClickListener {
            startActivity(Intent(this, GenerateCodeActivity::class.java))
        }

        binding.fabHistory.setOnClickListener {
            val items = arrayOf("保存", "查询")
            MaterialAlertDialogBuilder(this).setTitle("历史记录").setItems(
                items
            ) { _, which ->
                when (which) {
                    0 -> saveData()
                    1 -> {
                        val jsonStr = loadData()
                        val history =
                            JSON.parseObject(jsonStr, object : TypeReference<History>() {})
                        if (history.isNullOrEmpty()) {
                            showErrorToast("未找到历史记录\n请先进行保存")
                            return@setItems
                        }
                        val keys = mutableListOf<String>()
                        val valueIndexes = mutableListOf<MutableList<String>>()
                        history.forEach { pair ->
                            keys.add(pair.key)
                            valueIndexes.add(MutableList(pair.value.size) { it.toString() })
                        }
                        val historyPickerBinding =
                            DialogHistoryPickerBinding.inflate(layoutInflater)
                        (historyPickerBinding.actvHistoryDate as MaterialAutoCompleteTextView).setSimpleItems(
                            keys.toTypedArray()
                        )
                        var key = ""
                        var listIndex = -1
                        historyPickerBinding.actvHistoryDate.setOnItemClickListener { _, _, position, _ ->
                            (historyPickerBinding.actvHistoryIndex as MaterialAutoCompleteTextView).setSimpleItems(
                                valueIndexes[position].toTypedArray()
                            )
                            key = keys[position]
                        }
                        historyPickerBinding.actvHistoryIndex.setOnItemClickListener { _, _, position, _ ->
                            listIndex = position
                        }
                        MaterialAlertDialogBuilder(this).setView(historyPickerBinding.root)
                            .setTitle("筛选").setPositiveButton("确认") { _, _ ->
                                if (historyPickerBinding.actvHistoryDate.text.isEmpty() || historyPickerBinding.actvHistoryIndex.text.isEmpty()) {
                                    showErrorToast("选项不完整")
                                    return@setPositiveButton
                                }
                                // 创建意图对象
                                val itt = Intent(this, HistoryActivity::class.java)
                                // 设置传递键值对
//                                itt.putExtra("history_map_json", jsonStr)
                                itt.putExtra("history_map", history as Serializable)
                                itt.putExtra("key", key)
                                itt.putExtra("list_index", listIndex)
                                // 激活意图
                                startActivity(itt)
                            }.show()
                    }
                }
            }.show()
        }
    }

    override fun onResume() {
        super.onResume()
        readSettings()  // 读取设置
    }

    private fun readSettings() {
        // 获取 SharedPreferences 对象
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        // 读取字符串值，如果找不到对应的键，则返回默认值
        multiScanPrefix = sharedPreferences.getString("multi_scan_prefix", null)
        multiPicPrefix = sharedPreferences.getString("multi_pic_prefix", null)
        multiCodePrefix = sharedPreferences.getString("multi_code_prefix", null)
        // 读取布尔值
        parse = sharedPreferences.getBoolean("parse", true)
    }

    private fun handleViewImage(intent: Intent) {
        val imgUri = intent.data
        imgUri?.let {
            scanPic(uri = it)
        }
    }

    private fun handleSendImage(intent: Intent) {
        val imgUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        imgUri?.let {
            scanPic(uri = it)
        }
    }

    private fun handleSendMultipleImages(intent: Intent) {
        val imgUris = intent.getParcelableArrayListExtra<Uri?>(Intent.EXTRA_STREAM)
        imgUris?.let {
            scanPics(it)
        }
    }

    private fun saveData() {
        val file = File(applicationContext.filesDir, "history.json")
        val history = if (!file.exists()) {
            file.createNewFile()    // 如果不存在则新建文件
            sortedMapOf()
        } else {
            val jsonStr = file.readText()
            JSON.parseObject(jsonStr, object : TypeReference<History>() {})
        }
        val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.CHINA)
        val dateAsKey = sdf.format(Date(System.currentTimeMillis()))
        val strListAsValue = history.getOrElse(dateAsKey) { mutableListOf() }
        strListAsValue.add(binding.tvOutput.text.toString())
        history[dateAsKey] = strListAsValue
        val newJsonStr = JSON.toJSONString(history)
        file.writeText(newJsonStr)
        showInfoToast("日期：$dateAsKey\n索引：${strListAsValue.size - 1}\n保存成功")
    }

    private fun loadData(): String {
        val file = File(applicationContext.filesDir, "history.json")
        return if (file.exists()) file.readText() else ""
    }

    private fun scanPic(
        bitmap: Bitmap? = null, uri: Uri? = null, multiPicIdx: Int = -1, multiPicAmt: Int = -1
    ) {
        if (bitmap == null && uri == null) {
            return
        }
        val img = bitmap ?: try {
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        } catch (e: Exception) {
            e.printStackTrace()
            showSnackbar(binding.fabScanCode, "图片读取失败", Snackbar.LENGTH_LONG)
            return
        }
        val frame = HmsScanFrame(img)
        val option =
            HmsScanFrameOptions.Creator().setHmsScanTypes(HmsScan.ALL_SCAN_TYPE).setMultiMode(true)
                .setPhotoMode(true).setParseResult(true) // 默认值应为false，华为API文档有误
                .create()
        val results = ScanUtil.decode(this, frame, option).hmsScans
        if (results.isNullOrEmpty()) {
            printResults(results, multiPicIdx, multiPicAmt, true)
        } else {
            printResults(results, multiPicIdx, multiPicAmt)
        }
    }

    private fun scanPics(uris: List<Uri>) {
        for (i in uris.indices) {
            scanPic(uri = uris[i], multiPicIdx = i, multiPicAmt = uris.size)
        }
    }

    private fun printResults(
        results: Array<HmsScan>,
        multiPicIdx: Int = -1,
        multiPicAmt: Int = -1,
        emptyRes: Boolean = false
    ) {
        val codeAmt = results.size
        val newText = SpannableStringBuilder()
        if (binding.tvOutput.text.toString() != WAIT_FOR_SCAN) {
            newText.append(binding.tvOutput.text)
        }
        if (multiPicAmt == -1 || multiPicIdx == 0) {
            var prefix = "---------- 第 $scanCnt 次识别 ----------"
            if (!multiScanPrefix.isNullOrEmpty()) {
                val strSplit = multiScanPrefix!!.split("{n}")
                prefix = "${strSplit[0]}$scanCnt${strSplit[1]}"
            }
            newText.appendMySpan("$prefix\n", "#7400FF", 0.8)
            scanCnt++
        }
        if (multiPicIdx == 0) {
            newText.appendMySpan("检测到多图，数量：$multiPicAmt\n", "#1565C0", 0.8)
        }
        if (multiPicIdx != -1) {
            var prefix = "---------- 图 ${multiPicIdx + 1} ----------"
            if (!multiPicPrefix.isNullOrEmpty()) {
                val strSplit = multiPicPrefix!!.split("{n}")
                prefix = "${strSplit[0]}${multiPicIdx + 1}${strSplit[1]}"
            }
            newText.appendMySpan("$prefix\n", "#1565C0", 0.8)
        }
        if (emptyRes) {
            newText.append("无结果\n")
        } else {
            if (codeAmt > 1) {
                newText.appendMySpan("检测到多码，数量：$codeAmt\n", "#F57C00", 0.8)
                for (i in 0 until codeAmt) {
                    var prefix = "---------- 码 ${i + 1} ----------"
                    if (!multiCodePrefix.isNullOrEmpty()) {
                        val strSplit = multiCodePrefix!!.split("{n}")
                        prefix = "${strSplit[0]}${multiPicIdx + 1}${strSplit[1]}"
                    }
                    newText.appendMySpan("$prefix\n", "#F57C00", 0.8)
                    newText.append(concatCodeInfo(results[i]))
                }
            } else {
                newText.append(concatCodeInfo(results[0]))
            }
        }
        binding.tvOutput.text = newText
    }

    private fun SpannableStringBuilder.appendMySpan(
        str: String, colorHex: String? = null, relativeFontSize: Double? = null
    ): SpannableStringBuilder {
        val start = this.length
        val end = this.length + str.length
        this.append(str)
        colorHex?.let {
            this[start, end] = ForegroundColorSpan(Color.parseColor(colorHex))
        }
        relativeFontSize?.let {
            this[start, end] = RelativeSizeSpan(it.toFloat())
        }
        return this
    }

    /**
     * Apply for permissions.
     */
    private fun reqPerm(hwScan: Boolean) {
        XXPermissions.with(this).permission(Permission.CAMERA)
            .permission(Permission.READ_MEDIA_IMAGES).request(object : OnPermissionCallback {
                override fun onGranted(permissions: MutableList<String>, allGranted: Boolean) {
                    if (!allGranted) {
                        return
                    }
                    if (hwScan) {
                        ScanUtil.startScan(this@MainActivity, HW_SCAN_REQ_CODE, null)
                    } else {
                        val file =
                            File.createTempFile("tmp", ".jpg", this@MainActivity.externalCacheDir)
                        val uri = FileProvider.getUriForFile(
                            this@MainActivity, "com.jackcui.codesc.fileprovider", file
                        )
                        saveImageUri = uri
                        takePhotoLauncher.launch(uri)
                    }
                }

                override fun onDenied(permissions: MutableList<String>, doNotAskAgain: Boolean) {
                    if (doNotAskAgain) {
                        showErrorToast("权限请求被永久拒绝，请在系统设置中手动授权\n本应用仅申请必要权限，请放心授权")
                        // 如果是被永久拒绝就跳转到应用权限系统设置页面
                        XXPermissions.startPermissionActivity(this@MainActivity, permissions)
                    } else {
                        showErrorToast("权限请求被拒绝，请允许授予权限以正常使用此应用\n本应用仅申请必要权限，请放心授权")
                    }
                }
            })
    }

    /**
     * Event for receiving the activity result.
     *
     * @param requestCode Request code.
     * @param resultCode  Result code.
     * @param data        Result.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            return
        }
        if (requestCode == HW_SCAN_REQ_CODE) {
            val res = data.getParcelableExtra<HmsScan>(ScanUtil.RESULT)
            res?.let {
                printResults(arrayOf(it))
            }
        }
    }

    /**
     * 重置配置 fontScale：保持字体比例不变，始终为 1.
     */

//    override fun attachBaseContext(newBase: Context) {
//        super.attachBaseContext(newBase)
//        overrideFontScale(newBase)
//    }

//    private fun overrideFontScale(context: Context?) {
//        if (context == null) {
//            return
//        }
//        val configuration = context.resources.configuration
//        configuration.fontScale = 1f
//        applyOverrideConfiguration(configuration)
//    }

    private fun concatCodeInfo(res: HmsScan): String {
        val scanType = res.getScanType()
        val scanTypeForm = res.getScanTypeForm()
        val newText = StringBuilder()
        when (scanType) {
            HmsScan.QRCODE_SCAN_TYPE -> {
                newText.append("QR 码 - ")
            }

            HmsScan.AZTEC_SCAN_TYPE -> {
                newText.append("AZTEC 码 - ")
            }

            HmsScan.DATAMATRIX_SCAN_TYPE -> {
                newText.append("Data Matrix 码 - ")
            }

            HmsScan.PDF417_SCAN_TYPE -> {
                newText.append("PDF417 码 - ")
            }

            HmsScan.CODE93_SCAN_TYPE -> {
                newText.append("Code93 码 - ")
            }

            HmsScan.CODE39_SCAN_TYPE -> {
                newText.append("Code39 码 - ")
            }

            HmsScan.CODE128_SCAN_TYPE -> {
                newText.append("Code128 码 - ")
            }

            HmsScan.EAN13_SCAN_TYPE -> {
                newText.append("EAN13 码 - ")
            }

            HmsScan.EAN8_SCAN_TYPE -> {
                newText.append("EAN8 码 - ")
            }

            HmsScan.ITF14_SCAN_TYPE -> {
                newText.append("ITF14 码 - ")
            }

            HmsScan.UPCCODE_A_SCAN_TYPE -> {
                newText.append("UPC_A 码 - ")
            }

            HmsScan.UPCCODE_E_SCAN_TYPE -> {
                newText.append("UPC_E 码 - ")
            }

            HmsScan.CODABAR_SCAN_TYPE -> {
                newText.append("Codabar 码 - ")
            }

            HmsScan.WX_SCAN_TYPE -> {
                newText.append("微信码")
            }

            HmsScan.MULTI_FUNCTIONAL_SCAN_TYPE -> {
                newText.append("多功能码")
            }
        }
        when (scanTypeForm) {
            HmsScan.ARTICLE_NUMBER_FORM -> {
                newText.append("产品信息：")
                newText.append(res.getOriginalValue())
                newText.append("\n")
            }

            HmsScan.CONTACT_DETAIL_FORM -> {
                newText.append("联系人：\n")
                if (parse) {
                    val tmp = res.getContactDetail()
                    val peopleName = tmp.getPeopleName()
                    val tels = tmp.getTelPhoneNumbers()
                    val emailContentList = tmp.emailContents
                    val contactLinks = tmp.getContactLinks()
                    val company = tmp.getCompany()
                    val title = tmp.getTitle()
                    val addrInfoList = tmp.getAddressesInfos()
                    val note = tmp.getNote()
                    if (peopleName != null) {
                        newText.append("姓名： ")
                        newText.append(peopleName.getFullName())
                        newText.append("\n")
                    }
                    if (!tels.isNullOrEmpty()) {
                        newText.append("电话：\n")
                        for (tel in tels) {
                            when (tel.getUseType()) {
                                TelPhoneNumber.CELLPHONE_NUMBER_USE_TYPE -> {
                                    newText.append("  手机： ")
                                }

                                TelPhoneNumber.RESIDENTIAL_USE_TYPE -> {
                                    newText.append("  住家： ")
                                }

                                TelPhoneNumber.OFFICE_USE_TYPE -> {
                                    newText.append("  办公： ")
                                }

                                TelPhoneNumber.FAX_USE_TYPE -> {
                                    newText.append("  传真： ")
                                }

                                TelPhoneNumber.OTHER_USE_TYPE -> {
                                    newText.append("  其他： ")
                                }
                            }
                            newText.append(tel.getTelPhoneNumber())
                            newText.append("\n")
                        }
                    }
                    if (!emailContentList.isNullOrEmpty()) {
                        newText.append("邮箱： ")
                        val emails = ArrayList<String>()
                        for (email in emailContentList) {
                            emails.add(email.getAddressInfo())
                        }
                        newText.append(emails.joinToString())
                        newText.append("\n")
                    }
                    if (!contactLinks.isNullOrEmpty()) {
                        newText.append("URL： ")
                        newText.append(contactLinks.toList().joinToString())
                        newText.append("\n")
                    }
                    if (!company.isNullOrEmpty()) {
                        newText.append("公司： ")
                        newText.append(company)
                        newText.append("\n")
                    }
                    if (!title.isNullOrEmpty()) {
                        newText.append("职位： ")
                        newText.append(title)
                        newText.append("\n")
                    }
                    if (!addrInfoList.isNullOrEmpty()) {
                        newText.append("地址：\n")
                        for (addrInfo in addrInfoList) {
                            when (addrInfo.getAddressType()) {
                                AddressInfo.RESIDENTIAL_USE_TYPE -> {
                                    newText.append("  住家： ")
                                }

                                AddressInfo.OFFICE_TYPE -> {
                                    newText.append("  办公： ")
                                }

                                AddressInfo.OTHER_USE_TYPE -> {
                                    newText.append("  其他： ")
                                }
                            }
                            newText.append(addrInfo.getAddressDetails().toList().joinToString())
                            newText.append("\n")
                        }
                    }
                    if (!note.isNullOrEmpty()) {
                        newText.append("备注： ")
                        newText.append(note)
                        newText.append("\n")
                    }
                } else {
                    newText.append(res.getOriginalValue())
                    newText.append("\n")
                }
            }

            HmsScan.DRIVER_INFO_FORM -> {
                newText.append("驾照信息：\n")
                if (parse) {
                    val tmp = res.getDriverInfo()
                    val familyName = tmp.getFamilyName()
                    val middleName = tmp.getMiddleName()
                    val givenName = tmp.getGivenName()
                    val sex = tmp.getSex()
                    val dateOfBirth = tmp.getDateOfBirth()
                    val countryOfIssue = tmp.getCountryOfIssue()
                    val certType = tmp.getCertificateType()
                    val certNum = tmp.getCertificateNumber()
                    val dateOfIssue = tmp.getDateOfIssue()
                    val dateOfExpire = tmp.getDateOfExpire()
                    val province = tmp.getProvince()
                    val city = tmp.getCity()
                    val avenue = tmp.getAvenue()
                    val zipCode = tmp.getZipCode()
                    if (!familyName.isNullOrEmpty()) {
                        newText.append("姓： ")
                        newText.append(familyName)
                        newText.append("\n")
                    }
                    if (!middleName.isNullOrEmpty()) {
                        newText.append("中间名： ")
                        newText.append(middleName)
                        newText.append("\n")
                    }
                    if (!givenName.isNullOrEmpty()) {
                        newText.append("名： ")
                        newText.append(givenName)
                        newText.append("\n")
                    }
                    if (!sex.isNullOrEmpty()) {
                        newText.append("性别： ")
                        newText.append(sex)
                        newText.append("\n")
                    }
                    if (!dateOfBirth.isNullOrEmpty()) {
                        newText.append("出生日期： ")
                        newText.append(dateOfBirth)
                        newText.append("\n")
                    }
                    if (!countryOfIssue.isNullOrEmpty()) {
                        newText.append("驾照发放国： ")
                        newText.append(countryOfIssue)
                        newText.append("\n")
                    }
                    if (!certType.isNullOrEmpty()) {
                        newText.append("驾照类型： ")
                        newText.append(certType)
                        newText.append("\n")
                    }
                    if (!certNum.isNullOrEmpty()) {
                        newText.append("驾照号码： ")
                        newText.append(certNum)
                        newText.append("\n")
                    }
                    if (!dateOfIssue.isNullOrEmpty()) {
                        newText.append("发证日期： ")
                        newText.append(dateOfIssue)
                        newText.append("\n")
                    }
                    if (!dateOfExpire.isNullOrEmpty()) {
                        newText.append("过期日期： ")
                        newText.append(dateOfExpire)
                        newText.append("\n")
                    }
                    if (!province.isNullOrEmpty()) {
                        newText.append("省/州： ")
                        newText.append(province)
                        newText.append("\n")
                    }
                    if (!city.isNullOrEmpty()) {
                        newText.append("城市： ")
                        newText.append(city)
                        newText.append("\n")
                    }
                    if (!avenue.isNullOrEmpty()) {
                        newText.append("街道： ")
                        newText.append(avenue)
                        newText.append("\n")
                    }
                    if (!zipCode.isNullOrEmpty()) {
                        newText.append("邮政编码： ")
                        newText.append(zipCode)
                        newText.append("\n")
                    }
                } else {
                    newText.append(res.getOriginalValue())
                    newText.append("\n")
                }
            }

            HmsScan.EMAIL_CONTENT_FORM -> {
                newText.append("E-mail：\n")
                if (parse) {
                    val email = res.getEmailContent()
                    val addrInfo = email.getAddressInfo()
                    val subjectInfo = email.getSubjectInfo()
                    val bodyInfo = email.getBodyInfo()
                    if (!addrInfo.isNullOrEmpty()) {
                        newText.append("收件邮箱： ")
                        newText.append(addrInfo)
                        newText.append("\n")
                    }
                    if (!subjectInfo.isNullOrEmpty()) {
                        newText.append("主题： ")
                        newText.append(subjectInfo)
                        newText.append("\n")
                    }
                    if (!bodyInfo.isNullOrEmpty()) {
                        newText.append("内容： ")
                        newText.append(bodyInfo.substringBeforeLast(";;"))
                        newText.append("\n")
                    }
                } else {
                    newText.append(res.getOriginalValue())
                    newText.append("\n")
                }
            }

            HmsScan.EVENT_INFO_FORM -> {
                newText.append("日历事件：\n")
                if (parse) {
                    val tmp = res.getEventInfo()
                    val abstractInfo = tmp.getAbstractInfo()
                    val theme = tmp.getTheme()
                    val beginTimeInfo = tmp.getBeginTime()
                    val closeTimeInfo = tmp.getCloseTime()
                    val sponsor = tmp.getSponsor()
                    val placeInfo = tmp.getPlaceInfo()
                    val condition = tmp.getCondition()
                    if (!abstractInfo.isNullOrEmpty()) {
                        newText.append("描述： ")
                        newText.append(abstractInfo)
                        newText.append("\n")
                    }
                    if (!theme.isNullOrEmpty()) {
                        newText.append("摘要： ")
                        newText.append(theme)
                        newText.append("\n")
                    }
                    if (beginTimeInfo != null) {
                        newText.append("开始时间： ")
                        newText.append(beginTimeInfo.originalValue)
                        newText.append("\n")
                    }
                    if (closeTimeInfo != null) {
                        newText.append("开始时间： ")
                        newText.append(closeTimeInfo.originalValue)
                        newText.append("\n")
                    }
                    if (!sponsor.isNullOrEmpty()) {
                        newText.append("组织者： ")
                        newText.append(sponsor)
                        newText.append("\n")
                    }
                    if (!placeInfo.isNullOrEmpty()) {
                        newText.append("地点： ")
                        newText.append(placeInfo)
                        newText.append("\n")
                    }
                    if (!condition.isNullOrEmpty()) {
                        newText.append("状态： ")
                        newText.append(condition)
                        newText.append("\n")
                    }
                } else {
                    newText.append(res.getOriginalValue())
                    newText.append("\n")
                }
            }

            HmsScan.ISBN_NUMBER_FORM -> {
                newText.append("ISBN 号：\n")
                newText.append(res.getOriginalValue())
                newText.append("\n")
            }

            HmsScan.LOCATION_COORDINATE_FORM -> {
                newText.append("坐标：\n")
                if (parse) {
                    val tmp = res.getLocationCoordinate()
                    val latitude = tmp.getLatitude()
                    val longitude = tmp.getLongitude()
                    newText.append("经度： ")
                    newText.append(longitude)
                    newText.append("\n")
                    newText.append("纬度： ")
                    newText.append(latitude)
                    newText.append("\n")
                } else {
                    newText.append(res.getOriginalValue())
                    newText.append("\n")
                }
            }

            HmsScan.PURE_TEXT_FORM -> {
                newText.append("文本：\n")
                newText.append(res.getOriginalValue())
                newText.append("\n")
            }

            HmsScan.SMS_FORM -> {
                newText.append("短信：\n")
                if (parse) {
                    val tmp = res.getSmsContent()
                    val destPhoneNumber = tmp.getDestPhoneNumber()
                    val msgContent = tmp.getMsgContent()
                    if (!destPhoneNumber.isNullOrEmpty()) {
                        newText.append("收信人： ")
                        newText.append(destPhoneNumber)
                        newText.append("\n")
                    }
                    if (!msgContent.isNullOrEmpty()) {
                        newText.append("内容： ")
                        newText.append(msgContent)
                        newText.append("\n")
                    }
                } else {
                    newText.append(res.getOriginalValue())
                    newText.append("\n")
                }
            }

            HmsScan.TEL_PHONE_NUMBER_FORM -> {
                newText.append("电话号码：\n")
                if (parse) {
                    val tmp = res.getTelPhoneNumber()
                    if (tmp != null) {
                        when (tmp.getUseType()) {
                            TelPhoneNumber.CELLPHONE_NUMBER_USE_TYPE -> {
                                newText.append("手机： ")
                            }

                            TelPhoneNumber.RESIDENTIAL_USE_TYPE -> {
                                newText.append("住家： ")
                            }

                            TelPhoneNumber.OFFICE_USE_TYPE -> {
                                newText.append("办公： ")
                            }

                            TelPhoneNumber.FAX_USE_TYPE -> {
                                newText.append("传真： ")
                            }

                            TelPhoneNumber.OTHER_USE_TYPE -> {
                                newText.append("其他： ")
                            }
                        }
                        newText.append(tmp.getTelPhoneNumber())
                        newText.append("\n")
                    }
                } else {
                    newText.append(res.getOriginalValue())
                    newText.append("\n")
                }
            }

            HmsScan.URL_FORM -> {
                newText.append("URL 链接：\n")
                if (parse) {
                    val tmp = res.getLinkUrl()
                    val theme = tmp.getTheme()
                    val linkValue = tmp.linkValue
                    if (!theme.isNullOrEmpty()) {
                        newText.append("标题： ")
                        newText.append(theme)
                        newText.append("\n")
                    }
                    if (!linkValue.isNullOrEmpty()) {
                        newText.append("链接： ")
                        newText.append(linkValue)
                        newText.append("\n")
                    }
                } else {
                    newText.append(res.getOriginalValue())
                    newText.append("\n")
                }
            }

            HmsScan.WIFI_CONNECT_INFO_FORM -> {
                newText.append("Wi-Fi 信息：\n")
                if (parse) {
                    val tmp = res.wiFiConnectionInfo
                    val ssid = tmp.getSsidNumber()
                    val pwd = tmp.getPassword()
                    val cipherMode = tmp.getCipherMode()
                    if (!ssid.isNullOrEmpty()) {
                        newText.append("接入点名称： ")
                        newText.append(ssid)
                        newText.append("\n")
                    }
                    if (!pwd.isNullOrEmpty()) {
                        newText.append("密码： ")
                        newText.append(pwd)
                        newText.append("\n")
                    }
                    newText.append("加密方式： ")
                    when (cipherMode) {
                        WiFiConnectionInfo.WPA_MODE_TYPE -> {
                            newText.append("WPA/WPA2")
                        }

                        WiFiConnectionInfo.WEP_MODE_TYPE -> {
                            newText.append("WEP")
                        }

                        WiFiConnectionInfo.NO_PASSWORD_MODE_TYPE -> {
                            newText.append("开放")
                        }

                        WiFiConnectionInfo.SAE_MODE_TYPE -> {
                            newText.append("WPA3")
                        }
                    }
                    newText.append("\n")
                    newText.append("隐藏： ")
                    if (res.getOriginalValue().contains("H:true", ignoreCase = true)) {
                        newText.append("是")
                    } else {
                        newText.append("否")
                    }
                    newText.append("\n")
                } else {
                    newText.append(res.getOriginalValue())
                    newText.append("\n")
                }
            }

            HmsScan.OTHER_FORM -> {
                newText.append("未知类型信息：\n")
                newText.append(res.getOriginalValue())
                newText.append("\n")
            }
        }
        return newText.toString()
    }
}