package cloud.wumboing.rpchat.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import cloud.wumboing.rpchat.R
import cloud.wumboing.rpchat.data.Character
import cloud.wumboing.rpchat.data.Group
import cloud.wumboing.rpchat.data.GroupMember
import cloud.wumboing.rpchat.data.Storage
import cloud.wumboing.rpchat.databinding.ActivityCreateGroupBinding
import cloud.wumboing.rpchat.databinding.ItemGroupMemberInputBinding
import cloud.wumboing.rpchat.util.clipToCircle
import cloud.wumboing.rpchat.util.wireLiveInitialsPreview
import java.io.File
import java.util.UUID

class CreateGroupActivity : AppCompatActivity() {

    private data class MemberDraft(
        val id: String = UUID.randomUUID().toString(),
        var avatarPath: String? = null,
        val binding: ItemGroupMemberInputBinding,
        val sourceCharacterId: String? = null
    )

    private lateinit var binding: ActivityCreateGroupBinding
    private lateinit var storage: Storage

    private val groupId = UUID.randomUUID().toString()
    private val memberDrafts = mutableListOf<MemberDraft>()
    private var pendingGroupAvatarPath: String? = null
    private var pendingMemberDraft: MemberDraft? = null

    private val pickGroupAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            if (cloud.wumboing.rpchat.util.AvatarPickHelper.isGifUri(this, uri)) {
                val outFile = File(storage.avatarsDir, "group_gif_${UUID.randomUUID()}.gif")
                if (cloud.wumboing.rpchat.util.AvatarPickHelper.saveGifDirectly(contentResolver, uri, outFile)) {
                    pendingGroupAvatarPath = outFile.absolutePath
                    val bmp = BitmapFactory.decodeFile(outFile.absolutePath)
                    if (bmp != null) binding.imgGroupAvatar.setImageBitmap(bmp)
                }
            } else {
                launchCrop(uri, forMember = false)
            }
        }
    }

    private val pickMemberAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val member = pendingMemberDraft
            if (member != null && cloud.wumboing.rpchat.util.AvatarPickHelper.isGifUri(this, uri)) {
                val outFile = File(storage.avatarsDir, "member_gif_${UUID.randomUUID()}.gif")
                if (cloud.wumboing.rpchat.util.AvatarPickHelper.saveGifDirectly(contentResolver, uri, outFile)) {
                    member.avatarPath = outFile.absolutePath
                    val bmp = BitmapFactory.decodeFile(outFile.absolutePath)
                    if (bmp != null) member.binding.imgMemberAvatar.setImageBitmap(bmp)
                }
            } else {
                launchCrop(uri, forMember = true)
            }
        }
    }

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(CropAvatarActivity.EXTRA_RESULT_PATH)
            if (path != null) {
                val member = pendingMemberDraft
                if (member != null) {
                    member.avatarPath = path
                    val bmp = BitmapFactory.decodeFile(path)
                    if (bmp != null) member.binding.imgMemberAvatar.setImageBitmap(bmp)
                } else {
                    pendingGroupAvatarPath = path
                    val bmp = BitmapFactory.decodeFile(path)
                    if (bmp != null) binding.imgGroupAvatar.setImageBitmap(bmp)
                }
            }
            pendingMemberDraft = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.imgGroupAvatar.clipToCircle()

        storage = Storage(this)

        binding.imgGroupAvatar.setOnClickListener {
            pendingMemberDraft = null
            pickGroupAvatarLauncher.launch("image/*")
        }
        binding.editGroupName.wireLiveInitialsPreview(binding.imgGroupAvatar, groupId) {
            pendingGroupAvatarPath != null
        }

        binding.btnAddMember.setOnClickListener { showAddMemberChooser() }
        binding.btnCreateGroup.setOnClickListener { createGroup() }

        addMemberRow()
    }

    private fun showAddMemberChooser() {
        val options = arrayOf(getString(R.string.pick_from_contacts), getString(R.string.add_member))
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPickFromContactsDialog()
                    1 -> addMemberRow()
                }
            }
            .show()
    }

    private fun showPickFromContactsDialog() {
        val alreadyPicked = memberDrafts.mapNotNull { it.sourceCharacterId }.toSet()
        val available = storage.loadCharacters().filter { it.id !in alreadyPicked }
        if (available.isEmpty()) {
            Toast.makeText(this, R.string.no_contacts_available, Toast.LENGTH_SHORT).show()
            return
        }
        val names = available.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_from_contacts)
            .setItems(names) { _, which -> addMemberRow(prefill = available[which]) }
            .show()
    }

    private fun addMemberRow(prefill: Character? = null) {
        val rowBinding = ItemGroupMemberInputBinding.inflate(LayoutInflater.from(this), binding.membersContainer, false)
        rowBinding.imgMemberAvatar.clipToCircle()
        val draft = MemberDraft(
            id = prefill?.id ?: UUID.randomUUID().toString(),
            avatarPath = prefill?.avatarPath,
            binding = rowBinding,
            sourceCharacterId = prefill?.id
        )

        if (prefill != null) {
            rowBinding.editMemberName.setText(prefill.name)
            prefill.avatarPath?.let { path ->
                val bmp = BitmapFactory.decodeFile(path)
                if (bmp != null) rowBinding.imgMemberAvatar.setImageBitmap(bmp)
            }
        }

        rowBinding.imgMemberAvatar.setOnClickListener {
            pendingMemberDraft = draft
            pickMemberAvatarLauncher.launch("image/*")
        }
        rowBinding.editMemberName.wireLiveInitialsPreview(rowBinding.imgMemberAvatar, draft.id) {
            draft.avatarPath != null
        }
        rowBinding.btnRemoveMember.setOnClickListener {
            binding.membersContainer.removeView(rowBinding.root)
            memberDrafts.remove(draft)
        }

        memberDrafts.add(draft)
        binding.membersContainer.addView(rowBinding.root)
    }

    private fun launchCrop(uri: Uri, forMember: Boolean) {
        val intent = Intent(this, CropAvatarActivity::class.java)
        intent.putExtra(CropAvatarActivity.EXTRA_IMAGE_URI, uri.toString())
        cropLauncher.launch(intent)
    }

    private fun createGroup() {
        val name = binding.editGroupName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.group_name_hint, Toast.LENGTH_SHORT).show()
            return
        }

        val members = memberDrafts.mapNotNull { draft ->
            val memberName = draft.binding.editMemberName.text.toString().trim()
            if (memberName.isEmpty()) null
            else GroupMember(id = draft.id, name = memberName, avatarPath = null).also { member ->
                draft.avatarPath?.let { path ->
                    member.avatarPath = copyCroppedToInternal(path, "member_${member.id}")
                }
                // anggota yang dibuat baru (bukan dari kontak yang sudah ada) otomatis
                // ikut muncul di daftar kontak juga
                if (draft.sourceCharacterId == null) {
                    storage.addCharacter(Character(id = member.id, name = member.name, avatarPath = member.avatarPath))
                }
            }
        }.toMutableList()

        val group = Group(id = groupId, name = name, members = members)
        pendingGroupAvatarPath?.let { path ->
            group.avatarPath = copyCroppedToInternal(path, "group_${group.id}")
        }
        storage.addGroup(group)

        val intent = Intent(this, GroupChatActivity::class.java)
        intent.putExtra(GroupChatActivity.EXTRA_GROUP_ID, group.id)
        startActivity(intent)
        finish()
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
