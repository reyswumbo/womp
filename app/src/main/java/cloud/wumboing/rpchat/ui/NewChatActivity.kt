package cloud.wumboing.rpchat.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import cloud.wumboing.rpchat.R
import cloud.wumboing.rpchat.adapter.CharacterAdapter
import cloud.wumboing.rpchat.data.ChatEntry
import cloud.wumboing.rpchat.data.Character
import cloud.wumboing.rpchat.data.Storage
import cloud.wumboing.rpchat.databinding.ActivityNewChatBinding
import cloud.wumboing.rpchat.databinding.DialogAddCharacterBinding
import cloud.wumboing.rpchat.util.clipToCircle
import cloud.wumboing.rpchat.util.wireLiveInitialsPreview
import java.io.File

class NewChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewChatBinding
    private lateinit var storage: Storage
    private var allEntries: List<ChatEntry> = emptyList()

    private var pendingAvatarCroppedPath: String? = null
    private var dialogBinding: DialogAddCharacterBinding? = null

    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            if (cloud.wumboing.rpchat.util.AvatarPickHelper.isGifUri(this, uri)) {
                val outFile = File(storage.avatarsDir, "char_gif_${java.util.UUID.randomUUID()}.gif")
                if (cloud.wumboing.rpchat.util.AvatarPickHelper.saveGifDirectly(contentResolver, uri, outFile)) {
                    pendingAvatarCroppedPath = outFile.absolutePath
                    val bmp = BitmapFactory.decodeFile(outFile.absolutePath)
                    if (bmp != null) dialogBinding?.imgAvatarPreview?.setImageBitmap(bmp)
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
                dialogBinding?.imgAvatarPreview?.let { iv ->
                    val bmp = BitmapFactory.decodeFile(path)
                    if (bmp != null) iv.setImageBitmap(bmp)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        storage = Storage(this)
        binding.recyclerAllContacts.layoutManager = LinearLayoutManager(this)

        binding.rowNewGroup.setOnClickListener {
            startActivity(Intent(this, CreateGroupActivity::class.java))
            finish()
        }
        binding.rowNewContact.setOnClickListener { showAddCharacterDialog() }

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filterEntries(s?.toString() ?: "") }
        })

        loadEntries()
    }

    private fun loadEntries() {
        val characterEntries = storage.loadCharacters().map { ChatEntry.from(it) }
        val groupEntries = storage.loadGroups().map { ChatEntry.from(it) }
        allEntries = characterEntries + groupEntries
        filterEntries(binding.editSearch.text.toString())
    }

    private fun filterEntries(query: String) {
        val filtered = if (query.isBlank()) {
            allEntries
        } else {
            allEntries.filter { it.name.contains(query, ignoreCase = true) }
        }

        binding.txtAllContactsLabel.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE

        binding.recyclerAllContacts.adapter = CharacterAdapter(
            items = filtered.toMutableList(),
            previewProvider = { entry -> storage.lastMessagePreview(entry.id) },
            timeProvider = { entry -> storage.lastMessageTimestamp(entry.id) },
            draftProvider = { null },
            onClick = { entry -> openEntry(entry) },
            onLongClick = { }
        )
    }

    private fun openEntry(entry: ChatEntry) {
        if (entry.isGroup) {
            storage.unhideGroup(entry.id)
            val intent = Intent(this, GroupChatActivity::class.java)
            intent.putExtra(GroupChatActivity.EXTRA_GROUP_ID, entry.id)
            startActivity(intent)
        } else {
            storage.unhide(entry.id)
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra(ChatActivity.EXTRA_CHARACTER_ID, entry.id)
            startActivity(intent)
        }
        finish()
    }

    private fun openCharacterChat(character: Character) {
        if (!character.visible) storage.unhide(character.id)
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra(ChatActivity.EXTRA_CHARACTER_ID, character.id)
        startActivity(intent)
        finish()
    }

    private fun launchCrop(uri: Uri) {
        val intent = Intent(this, CropAvatarActivity::class.java)
        intent.putExtra(CropAvatarActivity.EXTRA_IMAGE_URI, uri.toString())
        cropLauncher.launch(intent)
    }

    private fun showAddCharacterDialog() {
        pendingAvatarCroppedPath = null
        val db = DialogAddCharacterBinding.inflate(layoutInflater)
        db.imgAvatarPreview.clipToCircle()
        dialogBinding = db

        val newId = java.util.UUID.randomUUID().toString()
        db.editName.wireLiveInitialsPreview(db.imgAvatarPreview, newId) { pendingAvatarCroppedPath != null }

        db.imgAvatarPreview.setOnClickListener {
            pickAvatarLauncher.launch("image/*")
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.add_character)
            .setView(db.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = db.editName.text.toString().trim()
                val bio = db.editBio.text.toString().trim().ifEmpty { null }
                if (name.isNotEmpty()) {
                    val character = Character(id = newId, name = name, bio = bio)
                    pendingAvatarCroppedPath?.let {
                        character.avatarPath = copyCroppedToInternal(it, "char_${character.id}")
                    }
                    storage.addCharacter(character)
                    openCharacterChat(character)
                }
                dialogBinding = null
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                dialogBinding = null
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
}
