package com.droid.dolphy.permissions

import android.content.Context
import android.content.SharedPreferences
import com.droid.dolphy.plugin.PluginPermission

/**
 * User-level capability vetoes for installed plugins.
 *
 * A plugin declares what it wants in `__permissions__`; this policy records the
 * subset the user has switched off from the permission center. Vetoes are
 * applied when the plugin session is created, so every bridge sees the reduced
 * set and keeps its normal `permission_denied` behaviour — no bridge code has to
 * special-case this object.
 */
object PluginAccessPolicy {
    private const val PREFS = "dolphy_plugin_access"
    private const val KEY_PREFIX = "revoked_"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun revoked(pluginId: String): Set<PluginPermission> {
        val stored = prefs?.getStringSet(key(pluginId), emptySet()).orEmpty()
        return stored.mapNotNull { PluginPermission.fromToken(it) }.toSet()
    }

    fun setRevoked(pluginId: String, permission: PluginPermission, revoked: Boolean) {
        val updated = revoked(pluginId).toMutableSet()
        if (revoked) updated += permission else updated -= permission
        prefs?.edit()?.putStringSet(key(pluginId), updated.map { it.token }.toSet())?.apply()
    }

    /** Declared capabilities minus the user's vetoes. */
    fun effective(pluginId: String, declared: Set<PluginPermission>): Set<PluginPermission> =
        declared - revoked(pluginId)

    fun clear(pluginId: String) {
        prefs?.edit()?.remove(key(pluginId))?.apply()
    }

    private fun key(pluginId: String) = "$KEY_PREFIX$pluginId"
}
