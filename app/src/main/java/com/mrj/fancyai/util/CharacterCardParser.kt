package com.mrj.fancyai.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.mrj.fancyai.data.db.entity.CharacterEntity
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Utility for parsing AI character cards (PNG with tEXt/chara chunks or JSON files).
 */
object CharacterCardParser {

    data class ParsedCharacter(
        val entity: CharacterEntity,
        val avatar: Bitmap?
    )

    fun parse(context: Context, inputStream: InputStream, fileName: String): ParsedCharacter? {
        return try {
            if (fileName.lowercase().endsWith(".png")) {
                parsePng(inputStream)
            } else {
                parseJson(inputStream.bufferedReader().use { it.readText() })
            }
        } catch (e: Exception) {
            Log.e("CharacterCardParser", "Failed to parse card", e)
            null
        }
    }

    private fun parsePng(inputStream: InputStream): ParsedCharacter? {
        val bytes = inputStream.readBytes()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        
        // Extract metadata from PNG chunks (simplified implementation)
        // In a real scenario, we'd iterate through chunks looking for 'chara' or 'tEXt'
        // For now, we'll try to find a JSON-like string in the bytes
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        val jsonStart = text.indexOf("{\"")
        val jsonEnd = text.lastIndexOf("}")
        
        if (jsonStart != -1 && jsonEnd > jsonStart) {
            val jsonStr = text.substring(jsonStart, jsonEnd + 1)
            return parseJson(jsonStr, bitmap)
        }
        
        return null
    }

    private fun parseJson(jsonStr: String, avatar: Bitmap? = null): ParsedCharacter? {
        val json = JSONObject(jsonStr)
        val data = if (json.has("data")) json.getJSONObject("data") else json
        
        val name = data.optString("name", data.optString("char_name", "Imported Character"))
        val persona = data.optString("description", data.optString("persona", ""))
        val bio = data.optString("personality", data.optString("scenario", ""))
        
        val character = CharacterEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            handle = "@" + name.lowercase().replace("\\s".toRegex(), ""),
            bio = bio,
            persona = persona,
            avatarRef = null // Will be set after saving the bitmap
        )
        
        return ParsedCharacter(character, avatar)
    }
}
