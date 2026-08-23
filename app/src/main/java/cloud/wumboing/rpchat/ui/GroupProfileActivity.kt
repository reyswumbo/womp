package cloud.wumboing.rpchat.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import cloud.wumboing.rpchat.R
import cloud.wumboing.rpchat.adapter.GroupMemberAdapter
import cloud.wumboing.rpchat.adapter.StatusGalleryAdapter
import cloud.wumboing.rpchat.data.Group
import cloud.wumboing.rpchat.data.GroupMember
import cloud.wumboing.rpchat.data.Storage
import cloud.wumboing.rpchat.databinding.ActivityGroupProfileBinding
import cloud.wumboing.rpchat.databinding.DialogAddCharacterBinding
import cloud.wumboing.rpchat.util.clipToCircle
import cloud.wumboing.rpchat.util.wireLiveInitialsPreview
import java.io.File

class GroupProfileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP_ID = "extra_group_id"
    }

    private lateinit var binding: ActivityGroupProfileBinding
    private lateinit var storage: Storage
    private lateinit var group: Group
    private lateinit var memberAdapter: GroupMemberAdapter

    private val snapHelper = PagerSnapHelper()

    // untuk ubah foto grup ATAU foto anggota (dibedakan lewat pendingEditMember)
    private var pendingEditMember: GroupMember? = null
    private var memberDialogBinding: DialogAddCharacterBinding? = null
    private var pendingMemberAvatarPath: String? = null

    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            if (cloud.wumboing.rpchat.util.AvatarPickHelper.isGifUri(this, uri)) {
                val member = pendingEditMember
                if (member != null) {
                    val outFile = File(storage.avatarsDir, "member_gif_${java.util.UUID.randomUUID()}.gif")
                    if (cloud.wumboing.rpchat.util.AvatarPickHelper.saveGifDirectly(contentResolver, uri, outFile)) {
                        pendingMemberAvatarPath = outFile.absolutePath
                        val bmp = BitmapFactory.decodeFile(outFile.absolutePath)
                        if (bmp != null) memberDialogBinding?.imgAvatarPreview?.setImageBitmap(bmp)
                    }
                } else {
                    val outFile = File(storage.avatarsDir, "group_gif_${java.util.UUID.randomUUID()}.gif")
                    if (cloud.wumboing.rpchat.util.AvatarPickHelper.saveGifDirectly(contentResolver, uri, outFile)) {
                        group.avatarPath = outFile.absolutePath
                        storage.updateGroup(group)
                        refreshGallery()
                    }
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
                val member = pendingEditMember
                if (member != null) {
                    pendingMemberAvatarPath = path
                    memberDialogBinding?.imgAvatarPreview?.let { iv ->
                        val bmp = BitmapFactory.decodeFile(path)
                        if (bmp != null) iv.setImageBitmap(bmp)
                    }
                } else {
                    val outFile = File(storage.avatarsDir, "group_${group.id}.jpg")
                    File(path).copyTo(outFile, overwrite = true)
                    group.avatarPath = outFile.absolutePath
                    storage.updateGroup(group)
                    refreshGallery()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = Storage(this)
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID)
        val found = storage.loadGroups().firstOrNull { it.id == groupId }
        if (found == null) {
            finish()
            return
        }
        group = found

        binding.galleryRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        snapHelper.attachToRecyclerView(binding.galleryRecycler)
        refreshGallery()

        binding.txtProfileName.text = group.name
        binding.txtProfileName.setOnClickListener { showRenameGroupDialog() }

        memberAdapter = GroupMemberAdapter(group.members.toMutableList()) { member -> showEditMemberDialog(member) }
        binding.recyclerMembers.layoutManager = LinearLayoutManager(this)
        binding.recyclerMembers.adapter = memberAdapter
        refreshMembersList()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnGalleryMenu.setOnClickListener { showGroupMenu() }

        binding.btnProfileChat.setOnClickListener {
            val intent = Intent(this, GroupChatActivity::class.java)
            intent.putExtra(GroupChatActivity.EXTRA_GROUP_ID, group.id)
            startActivity(intent)
        }
        // btnProfileMute, btnProfileCall, btnProfileVideo sengaja tidak diberi aksi (belum berfungsi)
    }

    private fun refreshGallery() {
        val paths = group.avatarPath?.let { if (File(it).exists()) listOf(it) else emptyList() } ?: emptyList()
        binding.galleryRecycler.adapter = StatusGalleryAdapter(paths, group.name, group.id)
    }

    private fun refreshMembersList() {
        memberAdapter.update(group.members)
        binding.txtNoMembers.visibility = if (group.members.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showGroupMenu() {
        val options = arrayOf(getString(R.string.change_group_photo), getString(R.string.add_member))
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        pendingEditMember = null
                        pickAvatarLauncher.launch("image/*")
                    }
                    1 -> showAddMemberChooser()
                }
            }
            .show()
    }

    private fun showAddMemberChooser() {
        val options = arrayOf(getString(R.string.pick_from_contacts), getString(R.string.add_member))
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPickFromContactsDialog()
                    1 -> showAddMemberDialog()
                }
            }
            .show()
    }

    private fun showPickFromContactsDialog() {
        val existingIds = group.members.map { it.id }.toSet()
        val available = storage.loadCharacters().filter { it.id !in existingIds }
        if (available.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_contacts_available, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val names = available.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_from_contacts)
            .setItems(names) { _, which ->
                val character = available[which]
                val newMember = GroupMember(id = character.id, name = character.name, avatarPath = character.avatarPath)
                group.members.add(newMember)
                storage.updateGroup(group)
                refreshMembersList()
            }
            .show()
    }

    private fun showRenameGroupDialog() {
        val input = EditText(this)
        input.setText(group.name)
        input.setSelection(input.text.length)
        AlertDialog.Builder(this)
            .setTitle(R.string.group_name_hint)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    group.name = newName
                    storage.updateGroup(group)
                    binding.txtProfileName.text = group.name
                    refreshGallery()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddMemberDialog() {
        pendingMemberAvatarPath = null
        val db = DialogAddCharacterBinding.inflate(layoutInflater)
        db.imgAvatarPreview.clipToCircle()
        memberDialogBinding = db
        val newMemberId = java.util.UUID.randomUUID().toString()
        pendingEditMember = GroupMember(name = "") // penanda mode tambah

        db.editBio.visibility = View.GONE
        db.editName.wireLiveInitialsPreview(db.imgAvatarPreview, newMemberId) { pendingMemberAvatarPath != null }
        db.imgAvatarPreview.setOnClickListener { pickAvatarLauncher.launch("image/*") }

        AlertDialog.Builder(this)
            .setTitle(R.string.add_member)
            .setView(db.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = db.editName.text.toString().trim()
                if (name.isNotEmpty()) {
                    val newMember = GroupMember(id = newMemberId, name = name)
                    pendingMemberAvatarPath?.let { path ->
                        newMember.avatarPath = copyCroppedToInternal(path, "member_${newMember.id}")
                    }
                    group.members.add(newMember)
                    storage.updateGroup(group)
                    // anggota baru otomatis muncul juga sebagai kontak
                    storage.addCharacter(
                        cloud.wumboing.rpchat.data.Character(
                            id = newMember.id,
                            name = newMember.name,
                            avatarPath = newMember.avatarPath
                        )
                    )
                    refreshMembersList()
                    binding.txtProfileName.text = group.name
                }
                memberDialogBinding = null
                pendingEditMember = null
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                memberDialogBinding = null
                pendingEditMember = null
            }
            .show()
    }

    private fun showEditMemberDialog(member: GroupMember) {
        pendingMemberAvatarPath = null
        val db = DialogAddCharacterBinding.inflate(layoutInflater)
        db.imgAvatarPreview.clipToCircle()
        memberDialogBinding = db
        pendingEditMember = member

        db.editBio.visibility = View.GONE
        db.editName.setText(member.name)
        member.avatarPath?.let { path ->
            val f = File(path)
            if (f.exists()) {
                val bmp = BitmapFactory.decodeFile(path)
                if (bmp != null) db.imgAvatarPreview.setImageBitmap(bmp)
            }
        }
        db.editName.wireLiveInitialsPreview(db.imgAvatarPreview, member.id) {
            pendingMemberAvatarPath != null || (member.avatarPath != null && File(member.avatarPath!!).exists())
        }
        db.imgAvatarPreview.setOnClickListener { pickAvatarLauncher.launch("image/*") }

        AlertDialog.Builder(this)
            .setTitle(R.string.edit_member)
            .setView(db.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = db.editName.text.toString().trim()
                if (name.isNotEmpty()) member.name = name
                pendingMemberAvatarPath?.let { path ->
                    member.avatarPath = copyCroppedToInternal(path, "member_${member.id}")
                }
                storage.updateGroup(group)
                refreshMembersList()
                binding.txtProfileName.text = group.name
                memberDialogBinding = null
                pendingEditMember = null
            }
            .setNeutralButton(R.string.remove_member) { _, _ ->
                group.members.removeAll { it.id == member.id }
                storage.updateGroup(group)
                refreshMembersList()
                memberDialogBinding = null
                pendingEditMember = null
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                memberDialogBinding = null
                pendingEditMember = null
            }
            .show()
    }

    private fun launchCrop(uri: Uri) {
        val intent = Intent(this, CropAvatarActivity::class.java)
        intent.putExtra(CropAvatarActivity.EXTRA_IMAGE_URI, uri.toString())
        cropLauncher.launch(intent)
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
