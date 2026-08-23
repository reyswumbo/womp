package cloud.wumboing.rpchat.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import cloud.wumboing.rpchat.R
import cloud.wumboing.rpchat.adapter.CharacterAdapter
import cloud.wumboing.rpchat.data.ChatEntry
import cloud.wumboing.rpchat.data.Storage
import cloud.wumboing.rpchat.databinding.FragmentChatListBinding
import cloud.wumboing.rpchat.util.clipToCircle
import cloud.wumboing.rpchat.util.loadAvatarOrInitials

class ChatListFragment : Fragment() {

    private var _binding: FragmentChatListBinding? = null
    private val binding get() = _binding!!

    private lateinit var storage: Storage
    private lateinit var adapter: CharacterAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        storage = Storage(context)

        binding.imgMyAvatar.clipToCircle()
        (activity as? androidx.appcompat.app.AppCompatActivity)?.setSupportActionBar(binding.toolbar)
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.setDisplayShowTitleEnabled(false)

        adapter = CharacterAdapter(
            items = buildEntries().toMutableList(),
            previewProvider = { entry -> storage.lastMessagePreview(entry.id) },
            timeProvider = { entry -> storage.lastMessageTimestamp(entry.id) },
            draftProvider = { entry -> draftFor(entry) },
            onClick = { entry -> openChat(entry) },
            onLongClick = { entry -> showChatActionMenu(entry) }
        )
        binding.recyclerCharacters.layoutManager = LinearLayoutManager(context)
        binding.recyclerCharacters.adapter = adapter

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(context, NewChatActivity::class.java))
        }
        binding.profileHeader.setOnClickListener { openSettings() }
        binding.btnSettings.setOnClickListener { openSettings() }

        refreshList()
        updateProfileHeader()
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        refreshList()
        updateProfileHeader()
    }

    private fun openSettings() {
        startActivity(Intent(requireContext(), SettingsActivity::class.java))
    }

    private fun buildEntries(): List<ChatEntry> {
        val characterEntries = storage.visibleCharacters().map { ChatEntry.from(it) }
        val groupEntries = storage.visibleGroups().map { ChatEntry.from(it) }
        return (characterEntries + groupEntries).sortedByDescending { storage.lastMessageTimestamp(it.id) ?: 0L }
    }

    private fun draftFor(entry: ChatEntry): String? {
        return if (entry.isGroup) {
            storage.loadGroups().firstOrNull { it.id == entry.id }?.draftText
        } else {
            storage.loadCharacters().firstOrNull { it.id == entry.id }?.draftText
        }
    }

    private fun refreshList() {
        val list = buildEntries()
        adapter.update(list)
        binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateProfileHeader() {
        val profile = storage.loadProfile()
        binding.txtMyName.text = profile.name
        binding.imgMyAvatar.loadAvatarOrInitials(profile.avatarPath, profile.name, "self")
    }

    private fun openChat(entry: ChatEntry) {
        if (entry.isGroup) {
            val intent = Intent(requireContext(), GroupChatActivity::class.java)
            intent.putExtra(GroupChatActivity.EXTRA_GROUP_ID, entry.id)
            startActivity(intent)
        } else {
            val intent = Intent(requireContext(), ChatActivity::class.java)
            intent.putExtra(ChatActivity.EXTRA_CHARACTER_ID, entry.id)
            startActivity(intent)
        }
    }

    private fun confirmHide(entry: ChatEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(entry.name)
            .setMessage("Hapus obrolan ini dari daftar? Kontak/grup & riwayat chat tetap tersimpan, bisa dipanggil lagi lewat tombol +.")
            .setPositiveButton(R.string.delete_message) { _, _ ->
                if (entry.isGroup) storage.hideGroupFromChatList(entry.id) else storage.hideFromChatList(entry.id)
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showChatActionMenu(entry: ChatEntry) {
        val options = arrayOf(getString(R.string.delete_message), getString(R.string.mark_unread))
        AlertDialog.Builder(requireContext())
            .setTitle(entry.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmHide(entry)
                    1 -> showMarkUnreadDialog(entry)
                }
            }
            .show()
    }

    private fun showMarkUnreadDialog(entry: ChatEntry) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.unread_count_hint)
            setText("1")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.mark_unread)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val count = input.text.toString().toIntOrNull() ?: 1
                if (entry.isGroup) {
                    val group = storage.loadGroups().firstOrNull { it.id == entry.id } ?: return@setPositiveButton
                    group.unreadCount = count.coerceAtLeast(0)
                    storage.updateGroup(group)
                } else {
                    val character = storage.loadCharacters().firstOrNull { it.id == entry.id } ?: return@setPositiveButton
                    character.unreadCount = count.coerceAtLeast(0)
                    storage.updateCharacter(character)
                }
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
