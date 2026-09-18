package com.backupkit

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Minimal Google Drive v3 REST client (via OkHttp) - just what backups need: find-or-create a
 * folder, upload a blob, list, download, delete. Deliberately avoids the heavyweight Drive Java
 * client. All calls are blocking; run them off the main thread.
 */
internal class DriveBackupStore(private val accessToken: String) {

    data class DriveFile(val id: String, val name: String, val modifiedTime: String)

    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val octetType = "application/octet-stream".toMediaType()

    private fun get(url: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer $accessToken").get().build()

    /** Returns the id of a folder with [name] under [parentId] (root if null), creating it if absent. */
    fun ensureFolder(name: String, parentId: String? = null): String {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        val q = buildString {
            append("mimeType='application/vnd.google-apps.folder' and trashed=false and name='")
            append(escaped).append("'")
            if (parentId != null) append(" and '$parentId' in parents")
        }
        val listUrl = "https://www.googleapis.com/drive/v3/files?q=${enc(q)}&fields=${enc("files(id,name)")}&spaces=drive"
        client.newCall(get(listUrl)).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw explain("Drive folder lookup failed", resp.code, body)
            val files = JSONObject(body).optJSONArray("files")
            if (files != null && files.length() > 0) return files.getJSONObject(0).getString("id")
        }
        val meta = JSONObject()
            .put("name", name)
            .put("mimeType", "application/vnd.google-apps.folder")
        if (parentId != null) meta.put("parents", JSONArray().put(parentId))
        val create = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id")
            .header("Authorization", "Bearer $accessToken")
            .post(meta.toString().toRequestBody(jsonType))
            .build()
        client.newCall(create).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw explain("Drive folder create failed", resp.code, body)
            return JSONObject(body).getString("id")
        }
    }

    fun upload(folderId: String, name: String, bytes: ByteArray) {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folderId))
        val createReq = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id")
            .header("Authorization", "Bearer $accessToken")
            .post(meta.toString().toRequestBody(jsonType))
            .build()
        val fileId = client.newCall(createReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw explain("Drive create failed", resp.code, body)
            JSONObject(body).getString("id")
        }
        val mediaReq = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $accessToken")
            .patch(bytes.toRequestBody(octetType))
            .build()
        client.newCall(mediaReq).execute().use { resp ->
            if (!resp.isSuccessful) throw explain("Drive upload failed", resp.code, resp.body?.string().orEmpty())
        }
    }

    /** Files in [folderId], newest first. */
    fun list(folderId: String): List<DriveFile> {
        val q = "'$folderId' in parents and trashed=false"
        val url = "https://www.googleapis.com/drive/v3/files?q=${enc(q)}" +
            "&fields=${enc("files(id,name,modifiedTime)")}&orderBy=${enc("modifiedTime desc")}"
        client.newCall(get(url)).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw explain("Drive list failed", resp.code, body)
            val arr = JSONObject(body).optJSONArray("files") ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DriveFile(o.getString("id"), o.getString("name"), o.optString("modifiedTime", ""))
            }
        }
    }

    fun download(fileId: String): ByteArray {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
        client.newCall(get(url)).execute().use { resp ->
            if (!resp.isSuccessful) throw explain("Drive download failed", resp.code, resp.body?.string().orEmpty())
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    fun delete(fileId: String) {
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) {
                throw explain("Drive delete failed", resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Pulls Google's human-readable reason out of a Drive JSON error body for clearer messages. */
    private fun explain(op: String, code: Int, body: String): IOException {
        val detail = try {
            JSONObject(body).getJSONObject("error").optString("message", "")
        } catch (e: Exception) {
            ""
        }
        return IOException("$op ($code): ${detail.ifBlank { body.take(200) }}")
    }
}
