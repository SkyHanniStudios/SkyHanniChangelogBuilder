package at.hannibal2.skyhanni.changelog

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object GsonUtils {

    val gson = Gson()

    inline fun <reified T> readObject(json: String): T {
        return gson.fromJson(json, object : TypeToken<T>() {})
    }

}