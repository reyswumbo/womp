package cloud.wumboing.rpchat.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cloud.wumboing.rpchat.databinding.ItemStickerBinding
import cloud.wumboing.rpchat.util.BitmapUtils
import java.io.File

class StickerAdapter(
    private val items: MutableList<File>,
    private val onClick: (File) -> Unit,
    private val onLongClick: (File) -> Unit
) : RecyclerView.Adapter<StickerAdapter.VH>() {

    inner class VH(val binding: ItemStickerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemStickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = items[position]
        val bmp = BitmapUtils.decodeSampledFromFile(file.absolutePath, 300)
        if (bmp != null) holder.binding.imgSticker.setImageBitmap(bmp)
        holder.binding.root.setOnClickListener { onClick(file) }
        holder.binding.root.setOnLongClickListener {
            onLongClick(file)
            true
        }
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<File>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
