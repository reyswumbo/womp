package cloud.wumboing.rpchat.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Group(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var avatarPath: String? = null,
    var members: MutableList<GroupMember> = mutableListOf(),
    var visible: Boolean = true,
    var pinnedMessageId: String? = null,
    var draftText: String? = null,
    var unreadCount: Int = 0
) {
    /** Ringkasan nama anggota, dipakai menggantikan bio: "arya, haCkor, akujawa9, faps, dll" */
    fun memberSummary(): String {
        if (members.isEmpty()) return ""
        val shown = members.take(4).joinToString(", ") { it.name }
        return if (members.size > 4) "$shown, dll" else shown
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("avatarPath", avatarPath ?: JSONObject.NULL)
        put("visible", visible)
        put("pinnedMessageId", pinnedMessageId ?: JSONObject.NULL)
        put("draftText", draftText ?: JSONObject.NULL)
        put("unreadCount", unreadCount)
        val arr = JSONArray()
        members.forEach { arr.put(it.toJson()) }
        put("members", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Group {
            val memberList = mutableListOf<GroupMember>()
            if (o.has("members") && !o.isNull("members")) {
                val arr = o.getJSONArray("members")
                for (i in 0 until arr.length()) memberList.add(GroupMember.fromJson(arr.getJSONObject(i)))
            }
            return Group(
                id = o.getString("id"),
                name = o.getString("name"),
                avatarPath = if (o.isNull("avatarPath")) null else o.optString("avatarPath"),
                members = memberList,
                visible = o.optBoolean("visible", true),
                pinnedMessageId = if (!o.has("pinnedMessageId") || o.isNull("pinnedMessageId")) null else o.optString("pinnedMessageId"),
                draftText = if (!o.has("draftText") || o.isNull("draftText")) null else o.optString("draftText"),
                unreadCount = o.optInt("unreadCount", 0)
            )
        }
    }
}
