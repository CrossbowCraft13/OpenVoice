package com.example.openvoice.accessibility

import android.graphics.Rect

/**
 * Lightweight, parsed representation of an accessibility node.
 * Owns no Android resources, safe to retain and pass between subsystems.
 */
data class UiNode(
    val text: String?,
    val description: String?,
    val className: String?,
    val packageName: String?,
    val viewId: String?,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isChecked: Boolean,
    val isCheckable: Boolean,
    val isFocusable: Boolean,
    val isEnabled: Boolean,
    val isPassword: Boolean,
    val isVisible: Boolean,
    val depth: Int,
    val children: List<UiNode>
) {
    val centerX: Int get() = (bounds.left + bounds.right) / 2
    val centerY: Int get() = (bounds.top + bounds.bottom) / 2
    val role: String get() = className?.substringAfterLast('.') ?: "unknown"
    val label: String get() = text ?: description ?: viewId ?: role
}

/**
 * Search criteria for locating accessibility nodes in the UI tree.
 */
data class SearchCriteria(
    val text: String? = null,
    val textContains: String? = null,
    val description: String? = null,
    val descriptionContains: String? = null,
    val className: String? = null,
    val viewId: String? = null,
    val isClickable: Boolean? = null,
    val isEditable: Boolean? = null,
    val isChecked: Boolean? = null,
    val isCheckable: Boolean? = null,
    val isScrollable: Boolean? = null,
    val isFocusable: Boolean? = null,
    val isEnabled: Boolean? = true,
    val maxDepth: Int = Int.MAX_VALUE
)
