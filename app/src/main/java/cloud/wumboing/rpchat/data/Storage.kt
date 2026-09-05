package cloud.wumboing.rpchat.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Penyimpanan sederhana berbasis file JSON di internal storage.
 * Tidak pakai Room/Gson supaya dependency & ukuran APK tetap minim.
 */
class Storage(context: Context) {

    private val charactersFile = File(context.filesDir, "characters.json")
    private val groupsFile = File(context.filesDir, "groups.json")
    private val profileFile = File(context.filesDir, "profile.json")
    private val settingsFile = File(context.filesDir, "settings.json")
    private val sessionsFile = File(context.filesDir, "sessions.json")
    private val chatsDir = File(context.filesDir, "chats").apply { mkdirs() }
    val avatarsDir = File(context.filesDir, "avatars").apply { mkdirs() }
    val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
    val statusDir = File(context.filesDir, "status").apply { mkdirs() }
    val stickersDir = File(context.filesDir, "stickers").apply { mkdirs() }

    fun loadStickers(): List<File> =
        stickersDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    fun loadSessions(): MutableList<ChatSession> {
        if (!sessionsFile.exists()) return mutableListOf()
        val arr = JSONArray(sessionsFile.readText())
        val list = mutableListOf<ChatSession>()
        for (i in 0 until arr.length()) {
            list.add(ChatSession.fromJson(arr.getJSONObject(i)))
        }
        return list
    }

    fun addSession(session: ChatSession) {
        val list = loadSessions()
        list.add(session)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sessionsFile.writeText(arr.toString())
    }

    fun loadSettings(): AppSettings {
        if (!settingsFile.exists()) return AppSettings()
        return AppSettings.fromJson(JSONObject(settingsFile.readText()))
    }

    fun saveSettings(settings: AppSettings) {
        settingsFile.writeText(settings.toJson().toString())
    }

    fun loadProfile(): UserProfile {
        if (!profileFile.exists()) return UserProfile()
        return UserProfile.fromJson(JSONObject(profileFile.readText()))
    }

    fun saveProfile(profile: UserProfile) {
        profileFile.writeText(profile.toJson().toString())
    }

    /** Semua kontak yang pernah dibuat (termasuk yang disembunyikan dari daftar chat). */
    fun loadCharacters(): MutableList<Character> {
        if (!charactersFile.exists()) return mutableListOf()
        val arr = JSONArray(charactersFile.readText())
        val list = mutableListOf<Character>()
        for (i in 0 until arr.length()) {
            list.add(Character.fromJson(arr.getJSONObject(i)))
        }
        return list
    }

    /** Hanya kontak yang sedang tampil di daftar chat utama. */
    fun visibleCharacters(): List<Character> = loadCharacters().filter { it.visible }

    fun saveCharacters(list: List<Character>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        charactersFile.writeText(arr.toString())
    }

    fun addCharacter(character: Character) {
        val list = loadCharacters()
        list.add(character)
        saveCharacters(list)
    }

    fun updateCharacter(character: Character) {
        val list = loadCharacters()
        val idx = list.indexOfFirst { it.id == character.id }
        if (idx >= 0) {
            list[idx] = character
            saveCharacters(list)
        }
    }

    /** Sembunyikan dari daftar chat utama, tapi kontak & riwayat chat tetap ada. */
    fun hideFromChatList(characterId: String) {
        val list = loadCharacters()
        val idx = list.indexOfFirst { it.id == characterId }
        if (idx >= 0) {
            list[idx].visible = false
            saveCharacters(list)
        }
    }

    fun unhide(characterId: String) {
        val list = loadCharacters()
        val idx = list.indexOfFirst { it.id == characterId }
        if (idx >= 0) {
            list[idx].visible = true
            saveCharacters(list)
        }
    }

    // ---------- Grup ----------

    fun loadGroups(): MutableList<Group> {
        if (!groupsFile.exists()) return mutableListOf()
        val arr = JSONArray(groupsFile.readText())
        val list = mutableListOf<Group>()
        for (i in 0 until arr.length()) {
            list.add(Group.fromJson(arr.getJSONObject(i)))
        }
        return list
    }

    fun visibleGroups(): List<Group> = loadGroups().filter { it.visible }

    fun saveGroups(list: List<Group>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        groupsFile.writeText(arr.toString())
    }

    fun addGroup(group: Group) {
        val list = loadGroups()
        list.add(group)
        saveGroups(list)
    }

    fun updateGroup(group: Group) {
        val list = loadGroups()
        val idx = list.indexOfFirst { it.id == group.id }
        if (idx >= 0) {
            list[idx] = group
            saveGroups(list)
        }
    }

    fun hideGroupFromChatList(groupId: String) {
        val list = loadGroups()
        val idx = list.indexOfFirst { it.id == groupId }
        if (idx >= 0) {
            list[idx].visible = false
            saveGroups(list)
        }
    }

    fun unhideGroup(groupId: String) {
        val list = loadGroups()
        val idx = list.indexOfFirst { it.id == groupId }
        if (idx >= 0) {
            list[idx].visible = true
            saveGroups(list)
        }
    }

    private fun chatFile(characterId: String) = File(chatsDir, "$characterId.json")

    fun loadMessages(characterId: String): MutableList<Message> {
        val f = chatFile(characterId)
        if (!f.exists()) return mutableListOf()
        val arr = JSONArray(f.readText())
        val list = mutableListOf<Message>()
        for (i in 0 until arr.length()) {
            list.add(Message.fromJson(arr.getJSONObject(i)))
        }
        return list
    }

    fun saveMessages(characterId: String, messages: List<Message>) {
        val arr = JSONArray()
        messages.forEach { arr.put(it.toJson()) }
        chatFile(characterId).writeText(arr.toString())
    }

    fun lastMessagePreview(characterId: String): String? {
        val messages = loadMessages(characterId)
        val last = messages.lastOrNull() ?: return null
        val body = when (last.mediaType) {
            "photo" -> "📷 Foto"
            "video" -> "🎬 Video"
            "audio" -> "🎵 Audio"
            "document" -> "📄 Dokumen"
            "sticker" -> "🖼️ Stiker"
            else -> last.text
        }
        if (last.isSelf || last.isNarrator) return body
        // Pesan dari lawan bicara: tampilkan nama pengirimnya di depan preview,
        // seperti "Nama: isi pesan". Untuk chat grup nama diambil dari senderName
        // pesan itu sendiri; untuk chat 1v1 diambil dari data karakternya.
        val senderLabel = last.senderName
            ?: loadCharacters().firstOrNull { it.id == characterId }?.name
        return if (!senderLabel.isNullOrEmpty()) "$senderLabel: $body" else body
    }

    fun lastMessageTimestamp(characterId: String): Long? {
        return loadMessages(characterId).lastOrNull()?.timestamp
    }

    /** Semua pesan dari semua karakter (termasuk yang sudah disembunyikan dari daftar chat). */
    fun allMessagesWithCharacterId(): List<Pair<String, Message>> {
        val result = mutableListOf<Pair<String, Message>>()
        chatsDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".json")) {
                val characterId = file.name.removeSuffix(".json")
                try {
                    val arr = JSONArray(file.readText())
                    for (i in 0 until arr.length()) {
                        result.add(characterId to Message.fromJson(arr.getJSONObject(i)))
                    }
                } catch (e: Exception) {
                    // abaikan file rusak
                }
            }
        }
        return result
    }
}
