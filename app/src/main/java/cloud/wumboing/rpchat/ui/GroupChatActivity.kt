package cloud.wumboing.rpchat.ui

import android.content.Intent
import android.database.Cursor
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
import cloud.wumboing.rpchat.adapter.StickerAdapter
import cloud.wumboing.rpchat.data.AppSettings
import cloud.wumboing.rpchat.data.ChatSession
import cloud.wumboing.rpchat.data.Group
import cloud.wumboing.rpchat.data.Message
import cloud.wumboing.rpchat.data.Storage
import cloud.wumboing.rpchat.databinding.ActivityChatBinding
import cloud.wumboing.rpchat.databinding.DialogStickerPickerBinding
import cloud.wumboing.rpchat.util.BitmapUtils
import cloud.wumboing.rpchat.util.clipToCircle
import cloud.wumboing.rpchat.util.loadAvatarOrInitials
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class GroupChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP_ID = "extra_group_id"
        private val REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "😡")
    }

    private data class PendingMedia(val path: String, val type: String, val displayName: String)

    private lateinit var binding: ActivityChatBinding
    private lateinit var storage: Storage
    private lateinit var adapter: MessageAdapter
    private lateinit var group: Group
    private var appSettings: AppSettings = AppSettings()
    private var sessionStartTime: Long = 0L

    private var replyingTo: Message? = null
    private var replyingToName: String? = null
    private var pendingMedia: PendingMedia? = null

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
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID)
        val found = storage.loadGroups().firstOrNull { it.id == groupId }
        if (found == null) {
            finish()
            return
        }
        group = found
        appSettings = storage.loadSettings()
        applyChatBackground()

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.characterHeader.setOnClickListener { openGroupProfile() }
        updateToolbarHeader()

        adapter = MessageAdapter(
            items = storage.loadMessages(group.id),
            selfAvatarProvider = { storage.loadProfile().avatarPath },
            selfNameProvider = { storage.loadProfile().name },
            otherAvatarProvider = { group.avatarPath },
            otherNameProvider = { group.name },
            otherSeed = group.id,
            settingsProvider = { appSettings },
            pinnedIdProvider = { group.pinnedMessageId },
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
        binding.btnSendAsOther.setOnClickListener { showMemberPicker() }
        binding.btnSendAsNarrator.setOnClickListener { sendMessage(isSelf = true, isNarrator = true) }
        binding.btnCancelReply.setOnClickListener { clearReply() }
        binding.btnCancelAttach.setOnClickListener { clearAttachment() }
        binding.btnAttach.setOnClickListener { showAttachOptions() }
        binding.btnEmoji.setOnClickListener { showStickerPicker() }
        binding.btnUnpin.setOnClickListener { unpinMessage() }
        binding.pinnedBar.setOnClickListener { scrollToPinnedMessage() }
        binding.btnMoreOptions.setOnClickListener { openGroupProfile() }
        // btnCall sengaja tidak diberi aksi (belum berfungsi)

        binding.editMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateSendIconsVisibility() }
        })
        updateSendIconsVisibility()
    }

    private fun openGroupProfile() {
        val intent = Intent(this, GroupProfileActivity::class.java)
        intent.putExtra(GroupProfileActivity.EXTRA_GROUP_ID, group.id)
        startActivity(intent)
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
        val messages = storage.loadMessages(group.id)
        messages.add(message)
        storage.saveMessages(group.id, messages)
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
        // refresh data grup (anggota bisa berubah dari layar profil grup)
        storage.loadGroups().firstOrNull { it.id == group.id }?.let { group = it }
        updateToolbarHeader()
        adapter.refreshAvatars()
        sessionStartTime = System.currentTimeMillis()
        if (group.unreadCount > 0) {
            group.unreadCount = 0
            storage.updateGroup(group)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::group.isInitialized || sessionStartTime == 0L) return
        val durationSeconds = (System.currentTimeMillis() - sessionStartTime) / 1000
        if (durationSeconds > 0) {
            storage.addSession(ChatSession(group.id, sessionStartTime, durationSeconds))
        }
        sessionStartTime = 0L
        saveDraft()
    }

    private fun restoreDraft() {
        val draft = group.draftText
        if (!draft.isNullOrEmpty()) {
            binding.editMessage.setText(draft)
            binding.editMessage.setSelection(binding.editMessage.text.length)
        }
    }

    private fun saveDraft() {
        val newDraft = binding.editMessage.text.toString().trim().ifEmpty { null }
        if (group.draftText != newDraft) {
            group.draftText = newDraft
            storage.updateGroup(group)
        }
    }

    private fun updateToolbarHeader() {
        binding.txtToolbarName.text = group.name
        val summary = group.memberSummary()
        if (summary.isNotEmpty()) {
            binding.txtToolbarBio.text = summary
            binding.txtToolbarBio.visibility = View.VISIBLE
        } else {
            binding.txtToolbarBio.visibility = View.GONE
        }
        binding.imgToolbarAvatar.loadAvatarOrInitials(group.avatarPath, group.name, group.id)
    }

    // ---------- Pilih anggota untuk kirim pesan ----------

    private fun showMemberPicker() {
        if (group.members.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_members_yet, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val names = group.members.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_member)
            .setItems(names) { _, which ->
                val member = group.members[which]
                sendMessage(
                    isSelf = false,
                    senderId = member.id,
                    senderName = member.name,
                    senderAvatarPath = member.avatarPath
                )
            }
            .show()
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
        val isPinned = message.id == group.pinnedMessageId
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
        group.pinnedMessageId = message.id
        storage.updateGroup(group)
        updatePinnedBar()
        adapter.refreshAvatars()
    }

    private fun unpinMessage() {
        group.pinnedMessageId = null
        storage.updateGroup(group)
        updatePinnedBar()
        adapter.refreshAvatars()
    }

    private fun updatePinnedBar() {
        val pinnedId = group.pinnedMessageId
        val pinnedMessage = pinnedId?.let { id -> storage.loadMessages(group.id).firstOrNull { it.id == id } }
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
        val pinnedId = group.pinnedMessageId ?: return
        val messages = storage.loadMessages(group.id)
        val index = messages.indexOfFirst { it.id == pinnedId }
        if (index >= 0) {
            binding.recyclerMessages.scrollToPosition(index)
        }
    }

    private fun deleteMessage(message: Message) {
        val messages = storage.loadMessages(group.id).toMutableList()
        val idx = messages.indexOfFirst { it.id == message.id }
        if (idx < 0) return
        messages.removeAt(idx)
        storage.saveMessages(group.id, messages)
        adapter.removeMessageById(message.id)
        if (message.id == group.pinnedMessageId) {
            group.pinnedMessageId = null
            storage.updateGroup(group)
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
                    val messages = storage.loadMessages(group.id).toMutableList()
                    val idx = messages.indexOfFirst { it.id == message.id }
                    if (idx >= 0) {
                        val updated = messages[idx].copy(text = newText, edited = true)
                        messages[idx] = updated
                        storage.saveMessages(group.id, messages)
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
                val messages = storage.loadMessages(group.id).toMutableList()
                val idx = messages.indexOfFirst { it.id == message.id }
                if (idx >= 0) {
                    val current = messages[idx]
                    val updated = current.copy(reaction = if (current.reaction == emoji) null else emoji)
                    messages[idx] = updated
                    storage.saveMessages(group.id, messages)
                    adapter.updateMessageById(message.id, updated)
                }
            }
            .show()
    }

    private fun startReply(message: Message) {
        replyingTo = message
        replyingToName = if (message.isSelf) storage.loadProfile().name else (message.senderName ?: group.name)
        binding.replyBar.visibility = View.VISIBLE
        binding.txtReplyLabel.text = "${getString(R.string.reply_to)} $replyingToName"
        binding.txtReplyPreview.text = if (message.text.isNotEmpty()) {
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

    private fun sendMessage(
        isSelf: Boolean,
        isNarrator: Boolean = false,
        senderId: String? = null,
        senderName: String? = null,
        senderAvatarPath: String? = null
    ) {
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
            mediaType = media?.type,
            senderId = senderId,
            senderName = senderName,
            senderAvatarPath = senderAvatarPath
        )

        val messages = storage.loadMessages(group.id)
        messages.add(message)
        storage.saveMessages(group.id, messages)

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
