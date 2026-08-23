package cloud.wumboing.rpchat.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cloud.wumboing.rpchat.data.ChatEntry
import cloud.wumboing.rpchat.databinding.ItemCharacterBinding
import cloud.wumboing.rpchat.util.ChatDateUtils
import cloud.wumboing.rpchat.util.clipToCircle
import cloud.wumboing.rpchat.util.loadAvatarOrInitials

class CharacterAdapter(
    private val items: MutableList<ChatEntry>,
    private val previewProvider: (ChatEntry) -> String?,
    private val timeProvider: (ChatEntry) -> Long?,
    private val draftProvider: (ChatEntry) -> String?,
    private val onClick: (ChatEntry) -> Unit,
    private val onLongClick: (ChatEntry) -> Unit
) : RecyclerView.Adapter<CharacterAdapter.VH>() {

    inner class VH(val binding: ItemCharacterBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCharacterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.imgAvatar.clipToCircle()
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.binding.txtName.text = entry.name

        val draft = draftProvider(entry)
        holder.binding.txtPreview.text = if (!draft.isNullOrEmpty()) {
            "Draft: $draft"
        } else {
            previewProvider(entry) ?: entry.fallbackPreview
        }

        val timestamp = timeProvider(entry)
        holder.binding.txtTime.text = if (timestamp != null) ChatDateUtils.formatChatTime(timestamp) else ""

        if (entry.unreadCount > 0) {
            holder.binding.txtUnreadBadge.visibility = View.VISIBLE
            holder.binding.txtUnreadBadge.text = if (entry.unreadCount > 99) "99+" else entry.unreadCount.toString()
        } else {
            holder.binding.txtUnreadBadge.visibility = View.GONE
        }

        holder.binding.imgAvatar.loadAvatarOrInitials(entry.avatarPath, entry.name, entry.id)

        holder.binding.root.setOnClickListener { onClick(entry) }
        holder.binding.root.setOnLongClickListener {
            onLongClick(entry)
            true
        }
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<ChatEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
