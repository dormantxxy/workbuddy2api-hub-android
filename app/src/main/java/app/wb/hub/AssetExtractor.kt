package app.wb.hub

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/** Recursively copies the assets tree (bin/, lib/, app/) into the app's private files dir. */
object AssetExtractor {
    fun ensureExtracted(ctx: Context, root: File) {
        // Skip re-extraction if the python runtime is already present and non-empty.
        val marker = File(root, "bin/python3.14")
        if (marker.exists() && marker.length() > 0) return
        copyDir(ctx, "", root)
    }

    private fun copyDir(ctx: Context, assetPath: String, dest: File) {
        val am = ctx.assets
        val children = am.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output, 1 shl 16) }
            }
        } else {
            dest.mkdirs()
            for (child in children) {
                val childPath = if (assetPath.isEmpty()) child else "$assetPath/$child"
                copyDir(ctx, childPath, File(dest, child))
            }
        }
    }
}
