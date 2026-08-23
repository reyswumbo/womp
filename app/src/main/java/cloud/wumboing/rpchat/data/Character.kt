package cloud.wumboing.rpchat.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Character(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var avatarPath: String? = null,
    var bio: String? = null,
    var visible: Boolean = true,
    var pinnedMessageId: String? = null,
    var draftText: String? = null,
    var username: String? = null,
    var statusPhotos: MutableList<String> = mutableListOf(),
    var unreadCount: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("avatarPath", avatarPath ?: JSONObject.NULL)
        put("bio", bio ?: JSONObject.NULL)
        put("visible", visible)
        put("pinnedMessageId", pinnedMessageId ?: JSONObject.NULL)
        put("draftText", draftText ?: JSONObject.NULL)
        put("username", username ?: JSONObject.NULL)
        put("statusPhotos", JSONArray(statusPhotos))
        put("unreadCount", unreadCount)
    }

    companion object {
        fun fromJson(o: JSONObject): Character {
            val statusList = mutableListOf<String>()
            if (o.has("statusPhotos") && !o.isNull("statusPhotos")) {
                val arr = o.getJSONArray("statusPhotos")
                for (i in 0 until arr.length()) statusList.add(arr.getString(i))
            }
            return Character(
                id = o.getString("id"),
                name = o.getString("name"),
                avatarPath = if (o.isNull("avatarPath")) null else o.optString("avatarPath"),
                bio = if (!o.has("bio") || o.isNull("bio")) null else o.optString("bio"),
                visible = o.optBoolean("visible", true),
                pinnedMessageId = if (!o.has("pinnedMessageId") || o.isNull("pinnedMessageId")) null else o.optString("pinnedMessageId"),
                draftText = if (!o.has("draftText") || o.isNull("draftText")) null else o.optString("draftText"),
                username = if (!o.has("username") || o.isNull("username")) null else o.optString("username"),
                statusPhotos = statusList,
                unreadCount = o.optInt("unreadCount", 0)
            )
        }
    }
}
