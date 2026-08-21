package io.github.crossbowcraft13.openvoice.accessibility

import android.graphics.Rect

/**
 * Normalized UI role classification for Android accessibility nodes.
 * Maps Android widget class names to a consistent role taxonomy.
 */
enum class UiRole {
    BUTTON, TEXT, IMAGE, INPUT, SWITCH, CHECKBOX, RADIO, LIST,
    LIST_ITEM, TOOLBAR, TAB_BAR, TAB_ITEM, NAV_BAR, TOGGLE,
    SLIDER, PROGRESS, SPINNER, DROPDOWN, DATE_PICKER, TIME_PICKER,
    DIALOG, ALERT, TOOLTIP, POPUP, MENU, MENU_ITEM, LINK,
    TAB_LAYOUT, VIEW_PAGER, CARD, CHIP, ICON, AVATAR, BADGE,
    DIVIDER, HEADER, FOOTER, SECTION, CONTAINER, SCROLLABLE,
    WEB_VIEW, MAP, VIDEO_PLAYER, AD, UNKNOWN;

    companion object {
        fun fromClassName(className: String?): UiRole = when {
            className == null -> UNKNOWN
            className.contains("Button", ignoreCase = true) -> BUTTON
            // EditText must be checked before TextView (both match "Text")
            className.contains("EditText", ignoreCase = true) -> INPUT
            className.contains("TextView", ignoreCase = true) -> TEXT
            className.contains("Image", ignoreCase = true) -> IMAGE
            className.contains("Switch", ignoreCase = true) -> SWITCH
            className.contains("CheckBox", ignoreCase = true) || className.contains("Checkbox", ignoreCase = true) -> CHECKBOX
            className.contains("RadioButton", ignoreCase = true) -> RADIO
            className.contains("ListView", ignoreCase = true) || className.contains("RecyclerView", ignoreCase = true) -> LIST
            className.contains("Spinner", ignoreCase = true) -> SPINNER
            className.contains("Dialog", ignoreCase = true) -> DIALOG
            className.contains("Alert", ignoreCase = true) -> ALERT
            className.contains("Toolbar", ignoreCase = true) || className.contains("ActionBar", ignoreCase = true) -> TOOLBAR
            className.contains("Tab", ignoreCase = true) -> TAB_ITEM
            className.contains("Menu", ignoreCase = true) -> MENU_ITEM
            className.contains("Progress", ignoreCase = true) -> PROGRESS
            className.contains("Slider", ignoreCase = true) || className.contains("SeekBar", ignoreCase = true) -> SLIDER
            className.contains("WebView", ignoreCase = true) -> WEB_VIEW
            className.contains("Map", ignoreCase = true) -> MAP
            className.contains("Card", ignoreCase = true) -> CARD
            className.contains("Chip", ignoreCase = true) -> CHIP
            className.contains("Search", ignoreCase = true) -> INPUT
            className.contains("DatePicker", ignoreCase = true) -> DATE_PICKER
            className.contains("TimePicker", ignoreCase = true) -> TIME_PICKER
            else -> UNKNOWN
        }
    }
}

/**
 * Normalized Semantic UI Node.
 * Provides a role-classified, structured representation of any accessibility node.
 */
data class SemanticUiNode(
    val role: UiRole,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val bounds: Rect,
    val centerX: Int,
    val centerY: Int,
    val isClickable: Boolean,
    val isEnabled: Boolean,
    val isVisible: Boolean,
    val isFocusable: Boolean,
    val isEditable: Boolean,
    val isChecked: Boolean?,
    val isCheckable: Boolean,
    val isScrollable: Boolean,
    val isPassword: Boolean,
    val depth: Int,
    val packageName: String?,
    val className: String?,
    val children: List<SemanticUiNode>
) {
    val label: String get() = text ?: contentDescription ?: viewId ?: role.name
    val interactive: Boolean get() = isClickable || isEditable || isFocusable || isCheckable
    val boundsString: String get() = "[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]"

    companion object {
        /**
         * Normalize an accessibility UiNode into a semantic node.
         */
        fun fromUiNode(node: UiNode, depth: Int = 0): SemanticUiNode {
            return SemanticUiNode(
                role = UiRole.fromClassName(node.className),
                text = node.text,
                contentDescription = node.description,
                viewId = node.viewId,
                bounds = node.bounds,
                centerX = (node.bounds.left + node.bounds.right) / 2,
                centerY = (node.bounds.top + node.bounds.bottom) / 2,
                isClickable = node.isClickable,
                isEnabled = node.isEnabled,
                isVisible = node.isVisible,
                isFocusable = node.isFocusable,
                isEditable = node.isEditable,
                isChecked = if (node.isCheckable) node.isChecked else null,
                isCheckable = node.isCheckable,
                isScrollable = node.isScrollable,
                isPassword = node.isPassword,
                depth = depth,
                packageName = node.packageName,
                className = node.className,
                children = node.children.map { fromUiNode(it, depth + 1) }
            )
        }

        /** Build a full semantic tree from the raw UiNode tree. */
        fun buildTree(root: UiNode?): SemanticUiNode? {
            return root?.let { fromUiNode(it) }
        }
    }
}

/**
 * Normalized screen state.
 */
data class ScreenState(
    val packageName: String = "",
    val activityName: String = "",
    val semanticTree: SemanticUiNode? = null,
    val interactiveElements: List<SemanticUiNode> = emptyList(),
    val textInputs: List<SemanticUiNode> = emptyList(),
    val clickableItems: List<SemanticUiNode> = emptyList(),
    val scrollableContainers: List<SemanticUiNode> = emptyList(),
    val dialogs: List<SemanticUiNode> = emptyList(),
    val keyboardVisible: Boolean = false,
    val capturedAt: Long = System.currentTimeMillis()
) {
    val hasDialogs: Boolean get() = dialogs.isNotEmpty()
    val isEmpty: Boolean get() = semanticTree == null

    companion object {
        fun fromUiTree(tree: UiNode?): ScreenState {
            val semantic = tree?.let { SemanticUiNode.buildTree(it) }
            val all = mutableListOf<SemanticUiNode>()

            fun collect(node: SemanticUiNode) {
                all.add(node)
                node.children.forEach { collect(it) }
            }
            semantic?.let { collect(it) }

            return ScreenState(
                packageName = tree?.packageName ?: "",
                activityName = "",
                semanticTree = semantic,
                interactiveElements = all.filter { it.interactive && it.isVisible },
                textInputs = all.filter { it.isEditable && it.isVisible },
                clickableItems = all.filter { it.isClickable && it.isVisible },
                scrollableContainers = all.filter { it.isScrollable && it.isVisible },
                dialogs = all.filter { it.role == UiRole.DIALOG || it.role == UiRole.ALERT }
            )
        }
    }
}
