package com.kivan.tether

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat

/**
 * The "Laptop" Direct Share tile (its category matches res/xml/shortcuts.xml). It is also the
 * conversation shortcut of the message notification, so that lands in the Conversations section.
 */
object Shortcuts {
    private const val SHARE_CATEGORY = "com.kivan.tether.SHARE"
    const val ID = "laptop"

    fun publish(context: Context) {
        val laptop = Notifier.laptop(context)
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel("Laptop")
            .setLongLabel("Laptop")
            .setIcon(laptop.icon)
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
            .setCategories(setOf(SHARE_CATEGORY))
            .setLongLived(true)
            .setPerson(laptop)
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }

    /** The share sheet ranks Direct Share tiles by reported use; without it ours sits below others. */
    fun used(context: Context) = ShortcutManagerCompat.reportShortcutUsed(context, ID)
}
