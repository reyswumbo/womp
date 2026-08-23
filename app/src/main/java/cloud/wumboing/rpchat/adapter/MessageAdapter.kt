package cloud.wumboing.rpchat.adapter

import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cloud.wumboing.rpchat.R
import cloud.wumboing.rpchat.data.AppSettings
import cloud.wumboing.rpchat.data.Message
import cloud.wumboing.rpchat.databinding.ItemDateSeparatorBinding
import cloud.wumboing.rpchat.databinding.ItemMessageBinding
import cloud.wumboing.rpchat.util.AvatarUtils
import cloud.wumboing.rpchat.util.BitmapUtils
import cloud.wumboing.rpchat.util.ChatDateUtils
import cloud.wumboing.rpchat.util.ThemeUtils
import cloud.wumboing.rpchat.util.clipToCircle
import cloud.wumboing.rpchat.util.loadAvatarOrInitials
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed class ChatRow {
    data class DateRow(val label: String) : ChatRow()
    data class MsgRow(val message: Message) : ChatRow()
}

class MessageAdapter(
    private val items: MutableList<Message>,
    private val selfAvatarProvider: () -> String?,
    private val selfNameProvider: () -> String,
    private val otherAvatarProvider: () -> String?,
    private val otherNameProvider: () -> String,
    private val otherSeed: String,
    private val settingsProvider: () -> AppSettings,
    private val pinnedIdProvider: () -> String?,
    private val onLongPress: (Message) -> Unit,
    private val onMediaClick: (Message) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_MESSAGE = 0
        private const val TYPE_DATE = 1
    }

    private var rows: List<ChatRow> = buildRows(items)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun buildRows(messages: List<Message>): List<ChatRow> {
        val result = mutableListOf<ChatRow>()
        var lastDay: String? = null
        for (m in messages) {
            val dayKey = ChatDateUtils.dayKey(m.timestamp)
            if (dayKey != lastDay) {
                result.add(ChatRow.DateRow(ChatDateUtils.formatDateHeader(m.timestamp)))
                lastDay = dayKey
            }
            result.add(ChatRow.MsgRow(m))
        }
        return result
    }

    inner class MsgVH(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)
    inner class DateVH(val binding: ItemDateSeparatorBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is ChatRow.DateRow -> TYPE_DATE
        is ChatRow.MsgRow -> TYPE_MESSAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_DATE) {
            DateVH(ItemDateSeparatorBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            val b = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            b.imgAvatarLeft.clipToCircle()
            b.imgAvatarRight.clipToCircle()
            MsgVH(b)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ChatRow.DateRow -> (holder as DateVH).binding.txtDateLabel.text = row.label
            is ChatRow.MsgRow -> bindMessage((holder as MsgVH).binding, row.message, position)
        }
    }

    private fun bindMessage(b: ItemMessageBinding, message: Message, position: Int) {
        val settings = settingsProvider()
        val isPinned = message.id == pinnedIdProvider()

        if (message.isNarrator) {
            b.contentRow.visibility = View.GONE
            b.txtNarrator.visibility = View.VISIBLE
            b.txtNarrator.typeface = ThemeUtils.typefaceFor(settings.fontFamily)
            b.txtNarrator.text = if (isPinned) "📌 ${message.text}" else message.text
            b.txtNarrator.setOnLongClickListener {
                onLongPress(message)
                true
            }
            return
        } else {
            b.contentRow.visibility = View.VISIBLE
            b.txtNarrator.visibility = View.GONE
        }

        b.txtMessage.text = message.text
        b.txtMessage.visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE
        b.txtMessage.typeface = ThemeUtils.typefaceFor(settings.fontFamily)
        b.txtMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp)

        val timeText = timeFormat.format(Date(message.timestamp))
        val timeLabel = if (message.edited) {
            "$timeText · ${b.root.context.getString(R.string.edited_label)}"
        } else {
            timeText
        }
        b.txtTime.text = if (isPinned) "📌 $timeLabel" else timeLabel
        b.imgReadReceipt.visibility = if (message.isSelf && !message.isNarrator) View.VISIBLE else View.GONE

        val rowParams = b.contentRow.layoutParams as? android.widget.LinearLayout.LayoutParams

        if (message.isSelf) {
            rowParams?.gravity = Gravity.END
            b.imgAvatarLeft.visibility = View.GONE
            b.imgAvatarRight.visibility = View.VISIBLE
            b.imgAvatarRight.loadAvatarOrInitials(selfAvatarProvider(), selfNameProvider(), "self")
        } else {
            rowParams?.gravity = Gravity.START
            b.imgAvatarRight.visibility = View.GONE
            b.imgAvatarLeft.visibility = View.VISIBLE
            val avatarPath = message.senderAvatarPath ?: otherAvatarProvider()
            val name = message.senderName ?: otherNameProvider()
            val seed = message.senderId ?: otherSeed
            b.imgAvatarLeft.loadAvatarOrInitials(avatarPath, name, seed)
        }
        b.contentRow.layoutParams = rowParams

        // Nama pengirim di bubble (khusus chat grup): tampil hanya saat ganti pengirim,
        // sembunyi kalau pesan sebelumnya masih dari pengirim yang sama.
        val showSenderName = !message.isSelf && message.senderId != null && run {
            val prevRow = if (position > 0) rows.getOrNull(position - 1) else null
            when (prevRow) {
                null -> true
                is ChatRow.DateRow -> true
                is ChatRow.MsgRow -> prevRow.message.isSelf || prevRow.message.senderId != message.senderId
            }
        }
        if (showSenderName) {
            b.txtSenderName.visibility = View.VISIBLE
            b.txtSenderName.text = message.senderName ?: ""
            b.txtSenderName.setTextColor(AvatarUtils.colorFor(message.senderId ?: otherSeed))
        } else {
            b.txtSenderName.visibility = View.GONE
        }

        if (!message.replyPreview.isNullOrEmpty()) {
            b.replyPreviewContainer.visibility = View.VISIBLE
            b.txtReplyNameInBubble.text = message.replyName ?: ""
            b.txtReplyPreviewInBubble.text = message.replyPreview
        } else {
            b.replyPreviewContainer.visibility = View.GONE
        }

        if (!message.reaction.isNullOrEmpty()) {
            b.txtReaction.visibility = View.VISIBLE
            b.txtReaction.text = message.reaction
        } else {
            b.txtReaction.visibility = View.GONE
        }

        b.photoFrame.visibility = View.GONE
        b.imgPlayOverlay.visibility = View.GONE
        b.mediaFileRow.visibility = View.GONE
        if (!message.mediaPath.isNullOrEmpty()) {
            when (message.mediaType) {
                "photo" -> {
                    val bmp = BitmapUtils.decodeSampledFromFile(message.mediaPath!!, 800)
                    if (bmp != null) {
                        b.imgMessagePhoto.setImageBitmap(bmp)
                        b.photoFrame.visibility = View.VISIBLE
                    }
                }
                "video" -> {
                    val thumb = BitmapUtils.videoThumbnail(message.mediaPath!!)
                    if (thumb != null) {
                        b.imgMessagePhoto.setImageBitmap(thumb)
                        b.photoFrame.visibility = View.VISIBLE
                        b.imgPlayOverlay.visibility = View.VISIBLE
                    } else {
                        b.mediaFileRow.visibility = View.VISIBLE
                        b.imgMediaIcon.setImageResource(R.drawable.ic_video)
                        b.txtMediaName.text = File(message.mediaPath!!).name
                    }
                }
                "audio", "document" -> {
                    b.mediaFileRow.visibility = View.VISIBLE
                    b.imgMediaIcon.setImageResource(
                        if (message.mediaType == "audio") R.drawable.ic_audio else R.drawable.ic_document
                    )
                    b.txtMediaName.text = File(message.mediaPath!!).name
                }
                "sticker" -> {
                    val bmp = BitmapUtils.decodeSampledFromFile(message.mediaPath!!, 500)
                    if (bmp != null) {
                        b.imgMessagePhoto.setImageBitmap(bmp)
                        b.photoFrame.visibility = View.VISIBLE
                    }
                }
            }
        }

        // Stiker tampil tanpa bubble/latar belakang
        if (message.mediaType == "sticker") {
            b.bubble.background = null
            b.bubble.setPadding(0, 0, 0, 0)
        } else if (message.isSelf) {
            b.bubble.background = ThemeUtils.bubbleDrawable(b.root.context, settings.bubbleSelfColor)
            b.bubble.setPadding(dp(b, 12), dp(b, 8), dp(b, 12), dp(b, 6))
        } else {
            b.bubble.background = ThemeUtils.bubbleDrawable(b.root.context, settings.bubbleOtherColor)
            b.bubble.setPadding(dp(b, 12), dp(b, 8), dp(b, 12), dp(b, 6))
        }

        b.contentRow.setOnClickListener {
            if (!message.mediaPath.isNullOrEmpty()) onMediaClick(message)
        }

        b.contentRow.setOnLongClickListener {
            onLongPress(message)
            true
        }
    }

    override fun getItemCount() = rows.size

    private fun dp(b: ItemMessageBinding, value: Int): Int =
        (value * b.root.resources.displayMetrics.density).toInt()

    fun getMessageAtPosition(position: Int): Message? = (rows.getOrNull(position) as? ChatRow.MsgRow)?.message

    fun submit(newItems: List<Message>) {
        items.clear()
        items.addAll(newItems)
        rows = buildRows(items)
        notifyDataSetChanged()
    }

    fun addMessage(message: Message) {
        items.add(message)
        rows = buildRows(items)
        notifyDataSetChanged()
    }

    fun removeMessageById(id: String) {
        items.removeAll { it.id == id }
        rows = buildRows(items)
        notifyDataSetChanged()
    }

    fun updateMessageById(id: String, updated: Message) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) items[idx] = updated
        rows = buildRows(items)
        notifyDataSetChanged()
    }

    fun refreshAvatars() {
        notifyDataSetChanged()
    }
}
