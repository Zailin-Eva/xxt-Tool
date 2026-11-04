package com.xxkg.board

import android.content.Context
import android.net.Uri
import androidx.core.util.AtomicFile
import java.io.File

/** 课程表 JSON 的读写、备份与恢复。 */
object TimetableStore {

    private const val FILE_NAME = "timetable.json"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun load(context: Context): TimetableData {
        val f = file(context)
        if (!f.exists()) return TimetableData()
        return try {
            TimetableCodec.decode(f.readText(Charsets.UTF_8)) ?: TimetableData()
        } catch (e: Exception) {
            TimetableData()
        }
    }

    fun save(context: Context, data: TimetableData): Boolean {
        val af = AtomicFile(file(context))
        var out: java.io.FileOutputStream? = null
        return try {
            out = af.startWrite()
            out.write(TimetableCodec.encode(data).toByteArray(Charsets.UTF_8))
            af.finishWrite(out)
            true
        } catch (e: Exception) {
            if (out != null) af.failWrite(out)
            false
        }
    }

    fun export(context: Context, uri: Uri, data: TimetableData): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(TimetableCodec.encode(data).toByteArray(Charsets.UTF_8))
            } != null
        } catch (e: Exception) {
            false
        }
    }

    fun import(context: Context, uri: Uri): TimetableData? {
        return try {
            val raw = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return null
            TimetableCodec.decode(raw)
        } catch (e: Exception) {
            null
        }
    }
}
