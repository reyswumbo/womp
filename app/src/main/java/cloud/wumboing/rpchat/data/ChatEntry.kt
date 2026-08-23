package cloud.wumboing.rpchat.data

/**
 * Representasi seragam untuk item di daftar chat, entah itu kontak (Character)
 * atau grup (Group), supaya satu adapter/list bisa menampilkan keduanya.
 */
data class ChatEntry(
    val id: String,
    val name: String,
    val avatarPath: String?,
    val isGroup: Boolean,
    val fallbackPreview: String,
    val unreadCount: Int
) {
    companion object {
        fun from(character: Character): ChatEntry = ChatEntry(
            id = character.id,
            name = character.name,
            avatarPath = character.avatarPath,
            isGroup = false,
            fallbackPreview = character.bio ?: "",
            unreadCount = character.unreadCount
        )

        fun from(group: Group): ChatEntry = ChatEntry(
            id = group.id,
            name = group.name,
            avatarPath = group.avatarPath,
            isGroup = true,
            fallbackPreview = group.memberSummary(),
            unreadCount = group.unreadCount
        )
    }
}
