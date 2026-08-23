package cloud.wumboing.rpchat.ui

import android.content.Intent
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cloud.wumboing.rpchat.R
import cloud.wumboing.rpchat.adapter.MessageAdapter
import cloud.wumboing.rpchat.data.Character
import cloud.wumboing.rpchat.data.Message
import cloud.wumboing.rpchat.data.Storage
import cloud.wumboing.rpchat.databinding.ActivityChatBinding
import cloud.wumboing.rpchat.databinding.DialogAddCharacterBinding
import cloud.wumboing.rpchat.databinding.DialogStickerPickerBinding
import cloud.wumboing.rpchat.adapter.StickerAdapter
import cloud.wumboing.rpchat.util.BitmapUtils
import cloud.wumboing.rpchat.util.clipToCircle
import cloud.wumboing.rpchat.util.loadAvatarOrInitials
import cloud.wumboing.rpchat.util.wireLiveInitialsPreview
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHARACTER_ID = "extra_character_id"
        private val REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "😡")
    }

    private data class PendingMedia(val path: String, val type: String, val displayName: String)

    private lateinit var binding: ActivityChatBinding
    private lateinit var storage: Storage
    private lateinit var adapter: MessageAdapter
    private lateinit var character: Character
    private var appSettings: cloud.wumboing.rpchat.data.AppSettings = cloud.wumboing.rpchat.data.AppSettings()
    private var sessionStartTime: Long = 0L

    private var replyingTo: Message? = null
    private var replyingToName: String? = null
    private var pendingMedia: PendingMedia? = null

    private var pendingAvatarCroppedPath: String? = null
    private var editDialogBinding: DialogAddCharacterBinding? = null

    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            if (cloud.wumboing.rpchat.util.AvatarPickHelper.isGifUri(this, uri)) {
                val outFile = File(storage.avatarsDir, "char_gif_${UUID.randomUUID()}.gif")
                if (cloud.wumboing.rpchat.util.AvatarPickHelper.saveGifDirectly(contentResolver, uri, outFile)) {
                    pendingAvatarCroppedPath = outFile.absolutePath
                    val bmp = BitmapFactory.decodeFile(outFile.absolutePath)
                    if (bmp != null) editDialogBinding?.imgAvatarPreview?.setImageBitmap(bmp)
                }
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
                editDialogBinding?.imgAvatarPreview?.let { iv ->
                    val bmp = BitmapFactory.decodeFile(path)
                    if (bmp != null) iv.setImageBitmap(bmp)
                }
            }
        }
    }

    private var pendingMediaType: String? = null
    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) handlePickedMedia(uri) }

    private val pickStickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) addSticker(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.imgToolbarAvatar.clipToCircle()

        storage = Storage(this)
        val characterId = intent.getStringExtra(EXTRA_CHARACTER_ID)
        val found = storage.loadCharacters().firstOrNull { it.id == characterId }
        if (found == null) {
            finish()
            return
        }
        character = found
        appSettings = storage.loadSettings()
        applyChatBackground()

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.characterHeader.setOnClickListener { showEditCharacterDialog() }
        updateToolbarHeader()

        adapter = MessageAdapter(
            items = storage.loadMessages(character.id),
            selfAvatarProvider = { storage.loadProfile().avatarPath },
            selfNameProvider = { storage.loadProfile().name },
            otherAvatarProvider = { character.avatarPath },
            otherNameProvider = { character.name },
            otherSeed = character.id,
            settingsProvider = { appSettings },
            pinnedIdProvider = { character.pinnedMessageId },
            onLongPress = { message -> showMessageOptions(message) },
            onMediaClick = { message -> openMedia(message) }
        )
        binding.recyclerMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerMessages.adapter = adapter
        attachSwipeToReply()
        scrollToBottom()
        updatePinnedBar()
        restoreDraft()

        binding.btnSendAsSelf.setOnClickListener { sendMessage(isSelf = true) }
        binding.btnSendAsOther.setOnClickListener { sendMessage(isSelf = false) }
        binding.btnSendAsNarrator.setOnClickListener { sendMessage(isSelf = true, isNarrator = true) }
        binding.btnCancelReply.setOnClickListener { clearReply() }
        binding.btnCancelAttach.setOnClickListener { clearAttachment() }
        binding.btnAttach.setOnClickListener { showAttachOptions() }
        binding.btnEmoji.setOnClickListener { showStickerPicker() }
        binding.btnUnpin.setOnClickListener { unpinMessage() }
        binding.pinnedBar.setOnClickListener { scrollToPinnedMessage() }
        binding.btnMoreOptions.setOnClickListener {
            val intent = Intent(this, CharacterProfileActivity::class.java)
            intent.putExtra(CharacterProfileActivity.EXTRA_CHARACTER_ID, character.id)
            startActivity(intent)
        }
        // btnCall sengaja tidak diberi aksi (belum berfungsi)

        binding.editMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateSendIconsVisibility() }
        })
        updateSendIconsVisibility()
    }

    private fun updateSendIconsVisibility() {
        val hasContent = binding.editMessage.text.toString().isNotEmpty() || pendingMedia != null
        binding.sendIconsContainer.visibility = if (hasContent) View.VISIBLE else View.GONE
        binding.btnAttach.visibility = if (hasContent) View.GONE else View.VISIBLE
    }

    private fun showStickerPicker() {
        val stickers = storage.loadStickers().toMutableList()
        if (stickers.isEmpty()) {
            pickStickerLauncher.launch("image/*")
            return
        }

        val sheetBinding = DialogStickerPickerBinding.inflate(layoutInflater)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.txtNoStickers.visibility = if (stickers.isEmpty()) View.VISIBLE else View.GONE
        sheetBinding.recyclerStickers.layoutManager = GridLayoutManager(this, 4)
        sheetBinding.recyclerStickers.adapter = StickerAdapter(
            items = stickers,
            onClick = { file ->
                sendSticker(file.absolutePath)
                dialog.dismiss()
            },
            onLongClick = { file -> confirmDeleteSticker(file, sheetBinding) }
        )

        sheetBinding.btnAddSticker.setOnClickListener {
            pickStickerLauncher.launch("image/*")
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun confirmDeleteSticker(file: File, sheetBinding: DialogStickerPickerBinding) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_sticker)
            .setMessage(R.string.delete_sticker_confirm)
            .setPositiveButton(R.string.delete_message) { _, _ ->
                file.delete()
                val remaining = storage.loadStickers().toMutableList()
                (sheetBinding.recyclerStickers.adapter as? StickerAdapter)?.update(remaining)
                sheetBinding.txtNoStickers.visibility = if (remaining.isEmpty()) View.VISIBLE else View.GONE
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addSticker(uri: Uri) {
        try {
            val outFile = File(storage.stickersDir, "${UUID.randomUUID()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            // abaikan jika gagal
        }
        showStickerPicker()
    }

    private fun sendSticker(path: String) {
        val message = Message(
            text = "",
            isSelf = true,
            mediaPath = path,
            mediaType = "sticker",
            replyToId = replyingTo?.id,
            replyPreview = binding.txtReplyPreview.text?.toString()?.takeIf { replyingTo != null },
            replyName = replyingToName
        )
        val messages = storage.loadMessages(character.id)
        messages.add(message)
        storage.saveMessages(character.id, messages)
        adapter.addMessage(message)
        clearReply()
        scrollToBottom()
    }

    private fun applyChatBackground() {
        binding.root.setBackgroundColor(appSettings.chatBackgroundColor)
    }

    override fun onResume() {
        super.onResume()
        if (!::adapter.isInitialized) return
        appSettings = storage.loadSettings()
        applyChatBackground()
        adapter.refreshAvatars()
        sessionStartTime = System.currentTimeMillis()
        if (character.unreadCount > 0) {
            character.unreadCount = 0
            storage.updateCharacter(character)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::character.isInitialized || sessionStartTime == 0L) return
        val durationSeconds = (System.currentTimeMillis() - sessionStartTime) / 1000
        if (durationSeconds > 0) {
            storage.addSession(cloud.wumboing.rpchat.data.ChatSession(character.id, sessionStartTime, durationSeconds))
        }
        sessionStartTime = 0L
        saveDraft()
    }

    private fun restoreDraft() {
        val draft = character.draftText
        if (!draft.isNullOrEmpty()) {
            binding.editMessage.setText(draft)
            binding.editMessage.setSelection(binding.editMessage.text.length)
        }
    }

    private fun saveDraft() {
        val text = binding.editMessage.text.toString()
        val newDraft = text.trim().ifEmpty { null }
        if (character.draftText != newDraft) {
            character.draftText = newDraft
            storage.updateCharacter(character)
        }
    }

    private fun updateToolbarHeader() {
        binding.txtToolbarName.text = character.name
        if (!character.bio.isNullOrEmpty()) {
            binding.txtToolbarBio.text = character.bio
            binding.txtToolbarBio.visibility = View.VISIBLE
        } else {
            binding.txtToolbarBio.visibility = View.GONE
        }
        binding.imgToolbarAvatar.loadAvatarOrInitials(character.avatarPath, character.name, character.id)
    }

    private fun launchCrop(uri: Uri) {
        val intent = Intent(this, CropAvatarActivity::class.java)
        intent.putExtra(CropAvatarActivity.EXTRA_IMAGE_URI, uri.toString())
        cropLauncher.launch(intent)
    }

    private fun showEditCharacterDialog() {
        pendingAvatarCroppedPath = null
        val db = DialogAddCharacterBinding.inflate(layoutInflater)
        db.imgAvatarPreview.clipToCircle()
        editDialogBinding = db

        db.editName.setText(character.name)
        db.editBio.setText(character.bio ?: "")
        character.avatarPath?.let { path ->
            val f = File(path)
            if (f.exists()) {
                val bmp = BitmapFactory.decodeFile(path)
                if (bmp != null) db.imgAvatarPreview.setImageBitmap(bmp)
            }
        }

        db.editName.wireLiveInitialsPreview(db.imgAvatarPreview, character.id) {
            pendingAvatarCroppedPath != null || (character.avatarPath != null && File(character.avatarPath!!).exists())
        }

        db.imgAvatarPreview.setOnClickListener {
            pickAvatarLauncher.launch("image/*")
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.edit_character)
            .setView(db.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = db.editName.text.toString().trim()
                if (name.isNotEmpty()) character.name = name
                character.bio = db.editBio.text.toString().trim().ifEmpty { null }
                pendingAvatarCroppedPath?.let { path ->
                    character.avatarPath = copyCroppedToInternal(path, "char_${character.id}")
                }
                storage.updateCharacter(character)
                updateToolbarHeader()
                adapter.refreshAvatars()
                editDialogBinding = null
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                editDialogBinding = null
            }
            .show()
    }

    private fun copyCroppedToInternal(tempPath: String, key: String): String? {
        return try {
            val ext = if (tempPath.endsWith(".gif", ignoreCase = true)) "gif" else "jpg"
            val outFile = File(storage.avatarsDir, "$key.$ext")
            File(tempPath).copyTo(outFile, overwrite = true)
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Attachment (photo/video/audio/dokumen) ----------

    private fun showAttachOptions() {
        val options = arrayOf(
            getString(R.string.attach_photo),
            getString(R.string.attach_video),
            getString(R.string.attach_audio),
            getString(R.string.attach_document)
        )
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { pendingMediaType = "photo"; pickMediaLauncher.launch("image/*") }
                    1 -> { pendingMediaType = "video"; pickMediaLauncher.launch("video/*") }
                    2 -> { pendingMediaType = "audio"; pickMediaLauncher.launch("audio/*") }
                    3 -> { pendingMediaType = "document"; pickMediaLauncher.launch("*/*") }
                }
            }
            .show()
    }

    private fun handlePickedMedia(uri: Uri) {
        val type = pendingMediaType ?: return
        val displayName = getDisplayName(uri) ?: "file"
        val key = "${UUID.randomUUID()}_$displayName"
        val outFile = File(storage.mediaDir, key)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            return
        }
        pendingMedia = PendingMedia(outFile.absolutePath, type, displayName)
        binding.attachBar.visibility = View.VISIBLE
        binding.txtAttachPreviewName.text = displayName
        binding.imgAttachPreviewIcon.setImageResource(
            when (type) {
                "photo" -> R.drawable.ic_photo
                "video" -> R.drawable.ic_video
                "audio" -> R.drawable.ic_audio
                else -> R.drawable.ic_document
            }
        )
        updateSendIconsVisibility()
    }

    private fun getDisplayName(uri: Uri): String? {
        var name: String? = null
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment
    }

    private fun clearAttachment() {
        pendingMedia = null
        binding.attachBar.visibility = View.GONE
        updateSendIconsVisibility()
    }

    private fun openMedia(message: Message) {
        val path = message.mediaPath ?: return
        if (!File(path).exists()) return
        when (message.mediaType) {
            "photo", "video", "audio" -> {
                val intent = Intent(this, MediaViewerActivity::class.java).apply {
                    putExtra(MediaViewerActivity.EXTRA_PATH, path)
                    putExtra(MediaViewerActivity.EXTRA_TYPE, message.mediaType)
                    putExtra(MediaViewerActivity.EXTRA_NAME, File(path).name)
                }
                startActivity(intent)
            }
            else -> openMediaExternally(message)
        }
    }

    private fun openMediaExternally(message: Message) {
        val path = message.mediaPath ?: return
        val file = File(path)
        if (!file.exists()) return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = when (message.mediaType) {
                "photo" -> "image/*"
                "video" -> "video/*"
                "audio" -> "audio/*"
                else -> "*/*"
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // tidak ada aplikasi yang bisa membuka file ini
        }
    }

    // ---------- Swipe to reply & long-press options ----------

    private fun attachSwipeToReply() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 0.35f

            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return 0
                val message = adapter.getMessageAtPosition(position) ?: return 0
                return if (message.isNarrator) 0 else super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val message = adapter.getMessageAtPosition(position) ?: return
                startReply(message)
                adapter.notifyItemChanged(position)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerMessages)
    }

    private fun showMessageOptions(message: Message) {
        val isPinned = message.id == character.pinnedMessageId
        val pinLabel = if (isPinned) getString(R.string.unpin_message) else getString(R.string.pin_message)
        val options = arrayOf(
            getString(R.string.delete_message),
            getString(R.string.edit_text),
            getString(R.string.give_reaction),
            pinLabel
        )
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> deleteMessage(message)
                    1 -> editMessageText(message)
                    2 -> showReactionPicker(message)
                    3 -> if (isPinned) unpinMessage() else pinMessage(message)
                }
            }
            .show()
    }

    private fun pinMessage(message: Message) {
        character.pinnedMessageId = message.id
        storage.updateCharacter(character)
        updatePinnedBar()
        adapter.refreshAvatars()
    }

    private fun unpinMessage() {
        character.pinnedMessageId = null
        storage.updateCharacter(character)
        updatePinnedBar()
        adapter.refreshAvatars()
    }

    private fun updatePinnedBar() {
        val pinnedId = character.pinnedMessageId
        val pinnedMessage = pinnedId?.let { id -> storage.loadMessages(character.id).firstOrNull { it.id == id } }
        if (pinnedMessage == null) {
            binding.pinnedBar.visibility = View.GONE
            binding.pinnedDivider.visibility = View.GONE
            return
        }
        binding.pinnedBar.visibility = View.VISIBLE
        binding.pinnedDivider.visibility = View.VISIBLE
        binding.txtPinnedPreview.text = pinnedMessage.text.ifEmpty {
            when (pinnedMessage.mediaType) {
                "photo" -> "📷 Foto"
                "video" -> "🎬 Video"
                "audio" -> "🎵 Audio"
                "document" -> "📄 Dokumen"
            "sticker" -> "🖼️ Stiker"
                else -> ""
            }
        }
    }

    private fun scrollToPinnedMessage() {
        val pinnedId = character.pinnedMessageId ?: return
        val messages = storage.loadMessages(character.id)
        val index = messages.indexOfFirst { it.id == pinnedId }
        if (index >= 0) {
            binding.recyclerMessages.scrollToPosition(index)
        }
    }

    private fun deleteMessage(message: Message) {
        val messages = storage.loadMessages(character.id).toMutableList()
        val idx = messages.indexOfFirst { it.id == message.id }
        if (idx < 0) return
        messages.removeAt(idx)
        storage.saveMessages(character.id, messages)
        adapter.removeMessageById(message.id)
        if (message.id == character.pinnedMessageId) {
            character.pinnedMessageId = null
            storage.updateCharacter(character)
            updatePinnedBar()
        }
    }

    private fun editMessageText(message: Message) {
        val input = EditText(this)
        input.setText(message.text)
        input.setSelection(input.text.length)
        input.hint = getString(R.string.edit_message_hint)

        AlertDialog.Builder(this)
            .setTitle(R.string.edit_text)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotEmpty()) {
                    val messages = storage.loadMessages(character.id).toMutableList()
                    val idx = messages.indexOfFirst { it.id == message.id }
                    if (idx >= 0) {
                        val updated = messages[idx].copy(text = newText, edited = true)
                        messages[idx] = updated
                        storage.saveMessages(character.id, messages)
                        adapter.updateMessageById(message.id, updated)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showReactionPicker(message: Message) {
        AlertDialog.Builder(this)
            .setItems(REACTIONS.toTypedArray()) { _, which ->
                val emoji = REACTIONS[which]
                val messages = storage.loadMessages(character.id).toMutableList()
                val idx = messages.indexOfFirst { it.id == message.id }
                if (idx >= 0) {
                    val current = messages[idx]
                    val updated = current.copy(reaction = if (current.reaction == emoji) null else emoji)
                    messages[idx] = updated
                    storage.saveMessages(character.id, messages)
                    adapter.updateMessageById(message.id, updated)
                }
            }
            .show()
    }

    private fun startReply(message: Message) {
        replyingTo = message
        replyingToName = if (message.isSelf) storage.loadProfile().name else character.name
        binding.replyBar.visibility = View.VISIBLE
        binding.txtReplyLabel.text = "${getString(R.string.reply_to)} $replyingToName"
        binding.txtReplyPreview.text = if (!message.text.isEmpty()) {
            message.text
        } else when (message.mediaType) {
            "photo" -> "📷 Foto"
            "video" -> "🎬 Video"
            "audio" -> "🎵 Audio"
            "document" -> "📄 Dokumen"
            "sticker" -> "🖼️ Stiker"
            else -> ""
        }
    }

    private fun clearReply() {
        replyingTo = null
        replyingToName = null
        binding.replyBar.visibility = View.GONE
    }

    private fun sendMessage(isSelf: Boolean, isNarrator: Boolean = false) {
        val text = binding.editMessage.text.toString().trim()
        val media = pendingMedia
        if (text.isEmpty() && media == null) return

        val message = Message(
            text = text,
            isSelf = isSelf,
            isNarrator = isNarrator,
            replyToId = replyingTo?.id,
            replyPreview = binding.txtReplyPreview.text?.toString()?.takeIf { replyingTo != null },
            replyName = replyingToName,
            mediaPath = media?.path,
            mediaType = media?.type
        )

        val messages = storage.loadMessages(character.id)
        messages.add(message)
        storage.saveMessages(character.id, messages)

        adapter.addMessage(message)
        binding.editMessage.text.clear()
        clearReply()
        clearAttachment()
        scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.recyclerMessages.post {
            if (adapter.itemCount > 0) {
                binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
            }
        }
    }
}
