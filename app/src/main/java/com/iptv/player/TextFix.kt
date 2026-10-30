package com.iptv.player

import android.view.KeyEvent
import android.widget.EditText

/**
 * Bazı TV klavyeleri rakamları "sayı tuş takımı" (NUMPAD) tuşu olarak gönderir.
 * Num Lock kapalıysa Android bunları ok tuşu sayar ve rakam alana yazılmaz.
 * Bu fonksiyon o tuşları yakalayıp rakamı kendisi yazar.
 */
fun EditText.acceptNumpad() {
    setOnKeyListener { _, keyCode, event ->
        val digit = when (keyCode) {
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)).toString()
            KeyEvent.KEYCODE_NUMPAD_DOT -> "."
            KeyEvent.KEYCODE_NUMPAD_COMMA -> ","
            else -> null
        } ?: return@setOnKeyListener false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val start = selectionStart.coerceAtLeast(0)
            val end = selectionEnd.coerceAtLeast(0)
            val max = filters.filterIsInstance<android.text.InputFilter.LengthFilter>().firstOrNull()?.max ?: Int.MAX_VALUE
            if (text.length - (maxOf(start, end) - minOf(start, end)) < max) {
                text.replace(minOf(start, end), maxOf(start, end), digit)
            }
        }
        true
    }
}
