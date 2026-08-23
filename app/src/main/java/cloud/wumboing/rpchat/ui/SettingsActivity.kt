package cloud.wumboing.rpchat.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import cloud.wumboing.rpchat.R
import cloud.wumboing.rpchat.data.AppSettings
import cloud.wumboing.rpchat.data.Storage
import cloud.wumboing.rpchat.databinding.ActivitySettingsBinding
import cloud.wumboing.rpchat.util.ThemeUtils
import cloud.wumboing.rpchat.util.clipToCircle
import cloud.wumboing.rpchat.util.loadAvatarOrInitials
import cloud.wumboing.rpchat.util.wireLiveInitialsPreview
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var storage: Storage
    private lateinit var settings: AppSettings

    private val bgColors = listOf(
        0xFF0E1621, 0xFF000000, 0xFF121212, 0xFF1A1A2E,
        0xFF1E1633, 0xFF0D1F17, 0xFF1F1712, 0xFF0B1A33
    ).map { it.toInt() }

    private val bubbleColors = listOf(
        0xFF2B5278, 0xFF182533, 0xFF4A2B78, 0xFF1F6F4A,
        0xFF7A2B2B, 0xFF7A4B2B, 0xFF1F6F6F, 0xFF7A2B5A,
        0xFF3A3A3A, 0xFF264D73
    ).map { it.toInt() }

    private var pendingAvatarCroppedPath: String? = null

    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            if (cloud.wumboing.rpchat.util.AvatarPickHelper.isGifUri(this, uri)) {
                saveGifAvatar(uri)
            } else {
                launchCrop(uri)
            }
        }
    }

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(CropAvatarActivity.EXTRA_RESULT_PATH)
            if (path != null) {
                pendingAvatarCroppedPath = path
                val profile = storage.loadProfile()
                profile.avatarPath = copyCroppedToInternal(path, "self_profile")
                storage.saveProfile(profile)
                showAvatarPreview(profile.avatarPath)
            }
        }
    }

    private fun saveGifAvatar(uri: Uri) {
        val outFile = File(storage.avatarsDir, "self_profile.gif")
        val success = cloud.wumboing.rpchat.util.AvatarPickHelper.saveGifDirectly(contentResolver, uri, outFile)
        if (success) {
            pendingAvatarCroppedPath = outFile.absolutePath
            val profile = storage.loadProfile()
            profile.avatarPath = outFile.absolutePath
            storage.saveProfile(profile)
            showAvatarPreview(profile.avatarPath)
        }
    }

    private fun showAvatarPreview(path: String?) {
        if (cloud.wumboing.rpchat.util.AvatarPickHelper.isGifPath(path)) {
            binding.imgSettingsAvatar.visibility = View.GONE
            binding.gifSettingsAvatar.visibility = View.VISIBLE
            binding.gifSettingsAvatar.setGifFile(path!!)
        } else {
            binding.gifSettingsAvatar.visibility = View.GONE
            binding.gifSettingsAvatar.clear()
            binding.imgSettingsAvatar.visibility = View.VISIBLE
            val profile = storage.loadProfile()
            binding.imgSettingsAvatar.loadAvatarOrInitials(path, profile.name, "self")
        }
    }

    private fun showAvatarSourceChooser() {
        val options = arrayOf(getString(R.string.pick_from_gallery), getString(R.string.paste_giphy_link))
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickAvatarLauncher.launch("image/*")
                    1 -> showGiphyUrlDialog()
                }
            }
            .show()
    }

    private fun showGiphyUrlDialog() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.giphy_url_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.paste_giphy_link)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) downloadGifAvatarFromUrl(url)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun downloadGifAvatarFromUrl(url: String) {
        val outFile = File(storage.avatarsDir, "self_profile.gif")
        android.widget.Toast.makeText(this, R.string.downloading_gif, android.widget.Toast.LENGTH_SHORT).show()
        cloud.wumboing.rpchat.util.AvatarPickHelper.downloadGifFromUrl(url, outFile) { success ->
            if (success) {
                val profile = storage.loadProfile()
                profile.avatarPath = outFile.absolutePath
                storage.saveProfile(profile)
                showAvatarPreview(profile.avatarPath)
            } else {
                android.widget.Toast.makeText(this, R.string.gif_download_failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.imgSettingsAvatar.clipToCircle()

        storage = Storage(this)
        settings = storage.loadSettings()

        setupProfileSection()
        setupFontButtons()
        setupFontSizeSeek()
        refreshChatBgRow()
        refreshBubbleSelfRow()
        refreshBubbleOtherRow()
        setupBackupButtons()
        setupAppIdentitySection()
    }

    // ---------- Backup / Restore ----------

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            val success = cloud.wumboing.rpchat.util.BackupManager.export(this, uri)
            android.widget.Toast.makeText(
                this,
                if (success) R.string.export_success else R.string.export_failed,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) confirmImport(uri)
    }

    private fun setupBackupButtons() {
        binding.btnExportData.setOnClickListener {
            val fileName = "rpchat-backup-${System.currentTimeMillis()}.zip"
            exportLauncher.launch(fileName)
        }
        binding.btnImportData.setOnClickListener {
            importLauncher.launch("application/zip")
        }
    }

    private fun confirmImport(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_confirm_title)
            .setMessage(R.string.import_confirm_message)
            .setPositiveButton(R.string.save) { _, _ ->
                val success = cloud.wumboing.rpchat.util.BackupManager.import(this, uri)
                android.widget.Toast.makeText(
                    this,
                    if (success) R.string.import_success else R.string.import_failed,
                    android.widget.Toast.LENGTH_LONG
                ).show()
                if (success) {
                    settings = storage.loadSettings()
                    setupProfileSection()
                    refreshFontButtons()
                    refreshChatBgRow()
                    refreshBubbleSelfRow()
                    refreshBubbleOtherRow()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Profil ----------

    private var liveAvatarWatcherWired = false

    private fun setupProfileSection() {
        val profile = storage.loadProfile()
        binding.editSettingsName.setText(profile.name)
        binding.editSettingsBio.setText(profile.bio ?: "")
        binding.imgSettingsAvatar.clipToCircle()
        binding.gifSettingsAvatar.clipToCircle()
        showAvatarPreview(profile.avatarPath)

        if (!liveAvatarWatcherWired) {
            liveAvatarWatcherWired = true
            binding.editSettingsName.wireLiveInitialsPreview(binding.imgSettingsAvatar, "self") {
                val p = storage.loadProfile().avatarPath
                !p.isNullOrEmpty() && File(p).exists()
            }
        }

        binding.touchSettingsAvatar.setOnClickListener {
            showAvatarSourceChooser()
        }

        binding.editSettingsName.addTextChangedListener(simpleWatcher {
            val p = storage.loadProfile()
            p.name = binding.editSettingsName.text.toString().trim().ifEmpty { p.name }
            storage.saveProfile(p)
        })

        binding.editSettingsBio.addTextChangedListener(simpleWatcher {
            val p = storage.loadProfile()
            p.bio = binding.editSettingsBio.text.toString().trim().ifEmpty { null }
            storage.saveProfile(p)
        })
    }

    private fun simpleWatcher(onChanged: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { onChanged() }
    }

    private fun launchCrop(uri: Uri) {
        val intent = Intent(this, CropAvatarActivity::class.java)
        intent.putExtra(CropAvatarActivity.EXTRA_IMAGE_URI, uri.toString())
        cropLauncher.launch(intent)
    }

    private fun copyCroppedToInternal(tempPath: String, key: String): String? {
        return try {
            val outFile = File(storage.avatarsDir, "$key.jpg")
            File(tempPath).copyTo(outFile, overwrite = true)
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Font ----------

    private fun setupFontButtons() {
        refreshFontButtons()
    }

    private fun refreshFontButtons() {
        binding.fontButtonsRow.removeAllViews()
        ThemeUtils.FONT_OPTIONS.forEach { (key, label) ->
            val btn = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                typeface = ThemeUtils.typefaceFor(key)
                setTextColor(if (settings.fontFamily == key) Color.WHITE else getColorCompat(R.color.text_secondary))
                background = ThemeUtils.bubbleDrawable(
                    this@SettingsActivity,
                    if (settings.fontFamily == key) getColorCompat(R.color.accent) else getColorCompat(R.color.bubble_other),
                    20f
                )
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.marginEnd = 12
                layoutParams = params
                setPadding(28, 20, 28, 20)
                setOnClickListener {
                    settings.fontFamily = key
                    storage.saveSettings(settings)
                    refreshFontButtons()
                    updateFontSizePreview()
                }
            }
            binding.fontButtonsRow.addView(btn)
        }
    }

    private fun getColorCompat(resId: Int): Int = resources.getColor(resId, theme)

    // ---------- Font size ----------

    private fun setupFontSizeSeek() {
        binding.seekFontSize.progress = (settings.fontSizeSp - 12f).toInt().coerceIn(0, 12)
        updateFontSizePreview()
        binding.seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                settings.fontSizeSp = (12 + progress).toFloat()
                updateFontSizePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                storage.saveSettings(settings)
            }
        })
    }

    private fun updateFontSizePreview() {
        binding.txtFontSizePreview.typeface = ThemeUtils.typefaceFor(settings.fontFamily)
        binding.txtFontSizePreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp)
        binding.txtFontSizePreview.text = "Aa — ${settings.fontSizeSp.toInt()}sp"
    }

    // ---------- Warna (preset + custom HSV) ----------

    private fun refreshChatBgRow() {
        renderSwatchRow(
            row = binding.chatBgRow,
            colors = bgColors,
            selected = settings.chatBackgroundColor,
            onPick = { color ->
                settings.chatBackgroundColor = color
                storage.saveSettings(settings)
                refreshChatBgRow()
            },
            onCustomPick = {
                showCustomColorPicker(settings.chatBackgroundColor) { color ->
                    settings.chatBackgroundColor = color
                    storage.saveSettings(settings)
                    refreshChatBgRow()
                }
            }
        )
    }

    private fun refreshBubbleSelfRow() {
        renderSwatchRow(
            row = binding.bubbleSelfRow,
            colors = bubbleColors,
            selected = settings.bubbleSelfColor,
            onPick = { color ->
                settings.bubbleSelfColor = color
                storage.saveSettings(settings)
                refreshBubbleSelfRow()
            },
            onCustomPick = {
                showCustomColorPicker(settings.bubbleSelfColor) { color ->
                    settings.bubbleSelfColor = color
                    storage.saveSettings(settings)
                    refreshBubbleSelfRow()
                }
            }
        )
    }

    private fun refreshBubbleOtherRow() {
        renderSwatchRow(
            row = binding.bubbleOtherRow,
            colors = bubbleColors,
            selected = settings.bubbleOtherColor,
            onPick = { color ->
                settings.bubbleOtherColor = color
                storage.saveSettings(settings)
                refreshBubbleOtherRow()
            },
            onCustomPick = {
                showCustomColorPicker(settings.bubbleOtherColor) { color ->
                    settings.bubbleOtherColor = color
                    storage.saveSettings(settings)
                    refreshBubbleOtherRow()
                }
            }
        )
    }

    private fun renderSwatchRow(
        row: LinearLayout,
        colors: List<Int>,
        selected: Int,
        onPick: (Int) -> Unit,
        onCustomPick: () -> Unit
    ) {
        row.removeAllViews()
        val scale = resources.displayMetrics.density
        val sizePx = (34 * scale).toInt()

        colors.forEach { color ->
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (color == selected) setStroke((2 * scale).toInt(), Color.WHITE)
                }
                val params = LinearLayout.LayoutParams(sizePx, sizePx)
                params.marginEnd = (8 * scale).toInt()
                layoutParams = params
                setOnClickListener { onPick(color) }
            }
            row.addView(swatch)
        }

        val customSwatch = TextView(this).apply {
            text = "+"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColorCompat(R.color.bubble_other))
                setStroke((1.5f * scale).toInt(), Color.WHITE)
            }
            val params = LinearLayout.LayoutParams(sizePx, sizePx)
            layoutParams = params
            setOnClickListener { onCustomPick() }
        }
        row.addView(customSwatch)
    }

    private fun showCustomColorPicker(initial: Int, onPick: (Int) -> Unit) {
        val scale = resources.displayMetrics.density
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * scale).toInt(), (16 * scale).toInt(), (24 * scale).toInt(), (8 * scale).toInt())
        }

        val preview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56 * scale).toInt())
            background = GradientDrawable().apply {
                setColor(Color.HSVToColor(hsv))
                cornerRadius = 12f * scale
            }
        }
        container.addView(preview)

        fun updatePreview() {
            (preview.background as GradientDrawable).setColor(Color.HSVToColor(hsv))
        }

        fun addSlider(label: String, max: Int, progress: Int, onChange: (Int) -> Unit) {
            val tv = TextView(this).apply {
                text = label
                setTextColor(getColorCompat(R.color.text_secondary))
                textSize = 13f
                setPadding(0, (12 * scale).toInt(), 0, 0)
            }
            container.addView(tv)
            val seek = SeekBar(this).apply {
                this.max = max
                this.progress = progress
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                        onChange(p)
                        updatePreview()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            container.addView(seek)
        }

        addSlider("Hue", 360, hsv[0].toInt()) { v -> hsv[0] = v.toFloat() }
        addSlider("Saturasi", 100, (hsv[1] * 100).toInt()) { v -> hsv[1] = v / 100f }
        addSlider("Kecerahan", 100, (hsv[2] * 100).toInt()) { v -> hsv[2] = v / 100f }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_custom_color)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ -> onPick(Color.HSVToColor(hsv)) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Nama & Ikon Aplikasi ----------

    private data class AppIdentity(val aliasName: String, val labelRes: Int, val iconRes: Int, val manifestDefaultEnabled: Boolean)

    private val identities = listOf(
        AppIdentity("cloud.wumboing.rpchat.LauncherDefault", R.string.identity_default, R.mipmap.ic_launcher, true),
        AppIdentity("cloud.wumboing.rpchat.LauncherNotes", R.string.identity_notes, R.mipmap.ic_launcher_notes, false),
        AppIdentity("cloud.wumboing.rpchat.LauncherCalc", R.string.identity_calc, R.mipmap.ic_launcher_calc, false),
        AppIdentity("cloud.wumboing.rpchat.LauncherWeather", R.string.identity_weather, R.mipmap.ic_launcher_weather, false)
    )

    private fun isAliasActive(identity: AppIdentity): Boolean {
        val state = packageManager.getComponentEnabledSetting(
            android.content.ComponentName(packageName, identity.aliasName)
        )
        return when (state) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> identity.manifestDefaultEnabled
        }
    }

    private fun setupAppIdentitySection() {
        refreshIdentityRows()
        setupCustomShortcut()
    }

    // ---------- Shortcut kustom (nama & ikon bebas) ----------

    private var pendingCustomShortcutIcon: android.graphics.Bitmap? = null

    private val pickCustomShortcutIconLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) launchCropForShortcut(uri) }

    private val cropForShortcutLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(CropAvatarActivity.EXTRA_RESULT_PATH)
            if (path != null) {
                val bmp = BitmapFactory.decodeFile(path)
                if (bmp != null) {
                    pendingCustomShortcutIcon = bmp
                    binding.imgCustomShortcutIcon.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun launchCropForShortcut(uri: Uri) {
        val intent = Intent(this, CropAvatarActivity::class.java)
        intent.putExtra(CropAvatarActivity.EXTRA_IMAGE_URI, uri.toString())
        cropForShortcutLauncher.launch(intent)
    }

    private fun setupCustomShortcut() {
        binding.imgCustomShortcutIcon.clipToCircle()
        binding.imgCustomShortcutIcon.setOnClickListener {
            pickCustomShortcutIconLauncher.launch("image/*")
        }
        binding.editCustomShortcutName.wireLiveInitialsPreview(binding.imgCustomShortcutIcon, "custom_shortcut") {
            pendingCustomShortcutIcon != null
        }
        binding.btnCreateCustomShortcut.setOnClickListener { createCustomShortcut() }
    }

    private fun createCustomShortcut() {
        val name = binding.editCustomShortcutName.text.toString().trim()
        if (name.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.custom_shortcut_name_hint, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val icon = pendingCustomShortcutIcon
            ?: cloud.wumboing.rpchat.util.AvatarUtils.initialsBitmap(name, "custom_shortcut", 192)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val shortcutManager = getSystemService(android.content.pm.ShortcutManager::class.java)
            if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
                val shortcutIntent = Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val shortcut = android.content.pm.ShortcutInfo.Builder(this, "custom_${System.currentTimeMillis()}")
                    .setShortLabel(name)
                    .setLongLabel(name)
                    .setIcon(android.graphics.drawable.Icon.createWithBitmap(icon))
                    .setIntent(shortcutIntent)
                    .build()
                shortcutManager.requestPinShortcut(shortcut, null)
                android.widget.Toast.makeText(this, R.string.custom_shortcut_created, android.widget.Toast.LENGTH_LONG).show()
            } else {
                android.widget.Toast.makeText(this, R.string.custom_shortcut_not_supported, android.widget.Toast.LENGTH_LONG).show()
            }
        } else {
            android.widget.Toast.makeText(this, R.string.custom_shortcut_not_supported, android.widget.Toast.LENGTH_LONG).show()
        }
    }


    private fun refreshIdentityRows() {
        binding.identityContainer.removeAllViews()
        val activeIdentity = identities.firstOrNull { isAliasActive(it) } ?: identities.first()
        val scale = resources.displayMetrics.density

        identities.forEach { identity ->
            val isActive = identity.aliasName == activeIdentity.aliasName
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((20 * scale).toInt(), (10 * scale).toInt(), (20 * scale).toInt(), (10 * scale).toInt())
                background = if (isActive) {
                    ThemeUtils.bubbleDrawable(this@SettingsActivity, getColorCompat(R.color.bubble_other), 12f)
                } else null
                isClickable = true
                isFocusable = true
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.bottomMargin = (6 * scale).toInt()
                layoutParams = params
            }

            val icon = android.widget.ImageView(this).apply {
                setImageResource(identity.iconRes)
                layoutParams = LinearLayout.LayoutParams((40 * scale).toInt(), (40 * scale).toInt())
            }
            row.addView(icon)

            val label = TextView(this).apply {
                text = getString(identity.labelRes)
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 14f
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                params.marginStart = (14 * scale).toInt()
                layoutParams = params
            }
            row.addView(label)

            if (isActive) {
                val check = android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(getColorCompat(R.color.accent))
                    layoutParams = LinearLayout.LayoutParams((20 * scale).toInt(), (20 * scale).toInt())
                }
                row.addView(check)
            }

            row.setOnClickListener {
                identities.forEach { other ->
                    val state = if (other.aliasName == identity.aliasName) {
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    }
                    packageManager.setComponentEnabledSetting(
                        android.content.ComponentName(packageName, other.aliasName),
                        state,
                        android.content.pm.PackageManager.DONT_KILL_APP
                    )
                }
                refreshIdentityRows()
            }

            binding.identityContainer.addView(row)
        }
    }
}
