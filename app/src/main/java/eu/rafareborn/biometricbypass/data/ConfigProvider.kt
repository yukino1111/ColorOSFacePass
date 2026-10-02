package eu.rafareborn.biometricbypass.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

class ConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "eu.rafareborn.biometricbypass.config"
        const val METHOD_GET_CONFIG = "getConfig"
        const val KEY_MODE = "mode"
        const val KEY_PACKAGES = "packages"
        const val KEY_HIDE_DIALOG = "hide_dialog"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_GET_CONFIG) {
            val ctx = context ?: return null
            val mode = ConfigManager.getMode(ctx)
            val packages = ArrayList(ConfigManager.getPackages(ctx))
            val hideDialog = ConfigManager.isHideDialog(ctx)
            return Bundle().apply {
                putString(KEY_MODE, mode)
                putStringArrayList(KEY_PACKAGES, packages)
                putBoolean(KEY_HIDE_DIALOG, hideDialog)
            }
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
