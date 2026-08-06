package com.sumatrapdf.reader.library

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

// shelf.py::discover_roots has no direct equivalent here: Android has no
// drive letters and no GetLogicalDrives. The shape is kept — look in the
// obvious places first, then sweep every storage volume end to end — but
// the places are Android's, and a scan can only see what
// MANAGE_EXTERNAL_STORAGE allows.

const val SCAN_DEPTH = 5
const val SCAN_BUDGET = 40000
const val SCAN_SCOPE = 2

fun hasStorageAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        Environment.getExternalStorageDirectory().canRead()
    }

private fun canonical(file: File): File = try {
    file.canonicalFile
} catch (_: Throwable) {
    file.absoluteFile
}

// Every volume the app can read: shared storage plus any SD card or OTG
// stick. StorageManager knows them all from Android 11; below that the
// app's own per-volume folder is the only public handle on them, and its
// fourth parent is the volume root.
fun volumeRoots(context: Context): List<File> {
    val out = mutableListOf<File>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { context.getSystemService(StorageManager::class.java) }
            .getOrNull()?.storageVolumes?.forEach { volume ->
                runCatching { volume.directory }.getOrNull()?.let { out.add(it) }
            }
    }
    runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { out.add(it) }
    runCatching { context.getExternalFilesDirs(null) }.getOrNull()?.forEach { dir ->
        var here: File? = dir
        repeat(4) { here = here?.parentFile }
        here?.let { out.add(it) }
    }
    runCatching { File("/storage").listFiles() }.getOrNull()?.forEach { entry ->
        if (entry.name != "self" && entry.name != "emulated") out.add(entry)
    }
    return out.map { canonical(it) }
        .filter { it.isDirectory && it.canRead() }
        .distinctBy { it.absolutePath }
}

private fun storageBases(context: Context): List<File> {
    val out = mutableListOf<File>()
    runCatching { Environment.getExternalStorageDirectory() }
        .getOrNull()?.let { out.add(it) }
    for (type in listOf(
        Environment.DIRECTORY_DOCUMENTS,
        Environment.DIRECTORY_DOWNLOADS,
        Environment.DIRECTORY_DCIM,
    )) {
        runCatching { Environment.getExternalStoragePublicDirectory(type) }
            .getOrNull()?.let { out.add(it) }
    }
    out.addAll(volumeRoots(context))
    return out.map { canonical(it) }
        .filter { it.isDirectory }
        .distinctBy { it.absolutePath }
}

// shelf.py::_scan_for_libraries — breadth-limited hunt for the first
// folder on each branch that actually holds books.
private fun scanForLibraries(root: File, depth: Int, budget: IntArray): List<File> {
    val hits = mutableListOf<File>()
    val stack = ArrayDeque<Pair<File, Int>>()
    stack.addLast(root to 0)
    while (stack.isNotEmpty() && budget[0] > 0) {
        val (folder, level) = stack.removeLast()
        budget[0]--
        val names = folder.listFiles() ?: continue
        if (names.any { it.isFile && isBookFile(it.name) }) {
            hits.add(folder)
            continue
        }
        if (level >= depth) continue
        for (sub in names) {
            if (!sub.isDirectory || skipDir(sub.name) || isRedirect(sub)) continue
            stack.addLast(sub to level + 1)
        }
    }
    return hits
}

// A folder that resolves somewhere else is another name for a place the
// sweep reaches on its own, so descending into it would scan the same
// books twice — or loop forever.
fun isRedirect(dir: File): Boolean = try {
    dir.canonicalPath != dir.absolutePath
} catch (_: Throwable) {
    true
}

// shelf.py::_outermost — a root that already contains another is dropped,
// so the same book is never scanned twice.
fun outermost(roots: List<String>): List<String> {
    val ordered = roots.sortedBy { it.length }
    val out = mutableListOf<String>()
    for (p in ordered) {
        val low = p.lowercase()
        if (out.any { low.startsWith(it.lowercase() + File.separator) }) continue
        out.add(p)
    }
    return out
}

// shelf.py::main_roots — the starting grounds. Scanned first so the wall
// fills with the obvious books while the device sweep is still running.
fun mainRoots(context: Context, extra: List<String> = emptyList()): List<String> {
    val seen = mutableSetOf<String>()
    val out = mutableListOf<String>()

    fun take(path: String?) {
        if (path.isNullOrBlank()) return
        val f = canonical(File(path))
        if (!f.isDirectory) return
        val abs = f.absolutePath
        val key = abs.lowercase()
        if (key in seen) return
        seen.add(key)
        out.add(abs)
    }

    extra.forEach { take(it) }

    val bases = storageBases(context)
    for (base in bases) {
        for (name in LIBRARY_DIR_NAMES) {
            take(File(base, name).takeIf { it.isDirectory }?.absolutePath)
            // Shared storage is conventionally capitalised.
            take(
                File(base, name.replaceFirstChar { it.uppercaseChar() })
                    .takeIf { it.isDirectory }?.absolutePath,
            )
        }
    }

    val budget = intArrayOf(SCAN_BUDGET)
    for (base in bases) {
        for (hit in scanForLibraries(base, SCAN_DEPTH, budget)) {
            take(hit.absolutePath)
        }
    }
    return outermost(out)
}

// shelf.py::discover_roots — the starting grounds first, then every
// volume in full, so a book anywhere on the device is found.
fun discoverRoots(context: Context, extra: List<String> = emptyList()): List<String> {
    val out = mainRoots(context, extra).toMutableList()
    val seen = out.map { it.lowercase() }.toMutableSet()
    for (volume in volumeRoots(context)) {
        val path = volume.absolutePath
        if (path.lowercase() in seen) continue
        seen.add(path.lowercase())
        out.add(path)
    }
    return out
}
