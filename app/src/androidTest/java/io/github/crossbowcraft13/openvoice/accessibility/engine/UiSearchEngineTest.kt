package io.github.crossbowcraft13.openvoice.accessibility.engine

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.crossbowcraft13.openvoice.accessibility.ScreenState
import io.github.crossbowcraft13.openvoice.accessibility.UiNode
import io.github.crossbowcraft13.openvoice.accessibility.UiRole
import io.github.crossbowcraft13.openvoice.util.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deep coverage of UiSearchEngine.scoreMatch: every query dimension
 * (relative position, coordinates, semantic similarity, descriptions,
 * roleName, className, packageName, visibility/enabled/checked/scrollable
 * filters) plus search strategies and result limiting.
 */
@RunWith(AndroidJUnit4::class)
class UiSearchEngineTest {

    private lateinit var search: UiSearchEngine

    private fun node(
        role: UiRole = UiRole.TEXT,
        text: String? = null,
        desc: String? = null,
        className: String? = "android.widget.TextView",
        pkg: String? = "com.example.app",
        left: Int = 0, top: Int = 0, right: Int = 100, bottom: Int = 50,
        clickable: Boolean = false, editable: Boolean = false, scrollable: Boolean = false,
        checked: Boolean = false, checkable: Boolean = false, focusable: Boolean = false,
        enabled: Boolean = true, visible: Boolean = true,
        children: List<UiNode> = emptyList()
    ): UiNode = UiNode(
        text = text, description = desc, className = className, packageName = pkg,
        viewId = null, bounds = Rect(left, top, right, bottom),
        isClickable = clickable, isEditable = editable, isScrollable = scrollable,
        isChecked = checked, isCheckable = checkable, isFocusable = focusable,
        isEnabled = enabled, isPassword = false, isVisible = visible,
        depth = 0, children = children
    )

    private fun sampleScreen(): ScreenState {
        val tree = node(
            role = UiRole.CONTAINER, text = "Screen", className = "android.widget.FrameLayout",
            left = 0, top = 0, right = 1080, bottom = 1920,
            children = listOf(
                node(text = "Header", left = 50, top = 50, right = 400, bottom = 100),
                node(text = "Password label", left = 100, top = 200, right = 400, bottom = 260),
                node(role = UiRole.INPUT, text = "secret input", className = "android.widget.EditText",
                    left = 100, top = 270, right = 700, bottom = 350,
                    editable = true, clickable = true, focusable = true),
                node(role = UiRole.BUTTON, text = "Save", className = "android.widget.Button",
                    left = 100, top = 400, right = 300, bottom = 470, clickable = true, focusable = true),
                node(role = UiRole.BUTTON, text = "Cancel", className = "android.widget.Button",
                    left = 400, top = 400, right = 600, bottom = 470, clickable = true, focusable = true),
                node(text = "Left label", left = 100, top = 600, right = 300, bottom = 660),
                node(text = "Right label", left = 700, top = 600, right = 900, bottom = 660),
                node(text = "Center anchor", left = 400, top = 800, right = 680, bottom = 860),
                node(role = UiRole.BUTTON, text = "left button", className = "android.widget.Button",
                    left = 100, top = 800, right = 380, bottom = 860, clickable = true),
                node(role = UiRole.BUTTON, text = "right button", className = "android.widget.Button",
                    left = 700, top = 800, right = 980, bottom = 860, clickable = true),
                node(role = UiRole.CHECKBOX, text = "Remember", className = "android.widget.CheckBox",
                    left = 100, top = 900, right = 300, bottom = 980,
                    checkable = true, checked = true, clickable = true),
                node(role = UiRole.CHECKBOX, text = "Unchecked", className = "android.widget.CheckBox",
                    left = 350, top = 900, right = 550, bottom = 980,
                    checkable = true, checked = false, clickable = true),
                node(role = UiRole.LIST, className = "android.widget.ListView",
                    left = 0, top = 1000, right = 1080, bottom = 1400, scrollable = true,
                    children = listOf(
                        node(role = UiRole.LIST_ITEM, text = "Row 1",
                            left = 10, top = 1010, right = 1070, bottom = 1130, clickable = true),
                        node(role = UiRole.LIST_ITEM, text = "Row 2",
                            left = 10, top = 1131, right = 1070, bottom = 1250, clickable = true)
                    )),
                node(role = UiRole.DIALOG, text = "Update available", className = "android.app.Dialog",
                    left = 100, top = 1500, right = 980, bottom = 1650,
                    children = listOf(
                        node(role = UiRole.BUTTON, text = "OK", className = "android.widget.Button",
                            left = 300, top = 1540, right = 500, bottom = 1610, clickable = true)
                    )),
                node(text = "Hidden", left = 0, top = 1700, right = 200, bottom = 1750, visible = false),
                node(role = UiRole.BUTTON, text = "Disabled button", className = "android.widget.Button",
                    left = 300, top = 1700, right = 500, bottom = 1780, enabled = false),
                node(role = UiRole.IMAGE, text = "Logo", desc = "App logo",
                    className = "android.widget.ImageView",
                    left = 700, top = 1700, right = 800, bottom = 1800),
                node(text = "Unique", pkg = "com.other.app", left = 100, top = 1850, right = 300, bottom = 1900)
            )
        )
        return ScreenState.fromUiTree(tree)
    }

    @Before
    fun setup() {
        Logger.init(true)
        search = UiSearchEngine()
    }

    // ── Text matching ───────────────────────────────────────────────

    @Test
    fun exactText_findsNode() {
        val screen = sampleScreen()
        assertEquals("Save", search.findFirst(screen, UiQuery(textExact = "Save"))?.text)
    }

    @Test
    fun exactText_isCaseInsensitive() {
        val screen = sampleScreen()
        assertEquals("Save", search.findFirst(screen, UiQuery(textExact = "save"))?.text)
    }

    @Test
    fun exactText_matchesContentDescription() {
        val screen = sampleScreen()
        val found = search.findFirst(screen, UiQuery(textExact = "App logo"))
        assertNotNull(found)
        assertEquals("Logo", found?.text)
    }

    @Test
    fun textContains_partialMatch() {
        val screen = sampleScreen()
        val results = search.findAll(screen, UiQuery(textContains = "ow"))
        assertTrue("expected Row nodes, got $results", results.any { it.text == "Row 1" })
        assertTrue(results.any { it.text == "Row 2" })
    }

    @Test
    fun textContains_caseInsensitive() {
        val screen = sampleScreen()
        assertEquals("Save", search.findFirst(screen, UiQuery(textContains = "save"))?.text)
    }

    @Test
    fun textContains_missingReturnsNull() {
        val screen = sampleScreen()
        assertNull(search.findFirst(screen, UiQuery(textContains = "zzz")))
    }

    @Test
    fun textQuery_takesPrecedenceOverTextContains() {
        val screen = sampleScreen()
        // text "Save" present, textContains "zzz" irrelevant when text is set.
        assertEquals("Save", search.findFirst(screen, UiQuery(text = "Save", textContains = "zzz"))?.text)
    }

    // ── Role matching ───────────────────────────────────────────────

    @Test
    fun roleFilter_buttonsOnly() {
        val screen = sampleScreen()
        val buttons = search.findAll(screen, UiQuery(role = UiRole.BUTTON, isEnabled = null))
        val labels = buttons.map { it.text }.toSet()
        assertTrue(labels.containsAll(setOf("Save", "Cancel", "left button", "right button", "OK", "Disabled button")))
    }

    @Test
    fun roleName_matchesRoleNameCaseInsensitive() {
        val screen = sampleScreen()
        val byUpper = search.findAll(screen, UiQuery(roleName = "BUTTON"))
        val byLower = search.findAll(screen, UiQuery(roleName = "button"))
        assertEquals(byUpper.size, byLower.size)
        assertTrue(byUpper.isNotEmpty())
    }

    @Test
    fun roleName_matchesFromClassName() {
        val screen = sampleScreen()
        val buttons = search.findAll(screen, UiQuery(roleName = "android.widget.Button"))
        assertTrue(buttons.any { it.text == "Save" })
    }

    @Test
    fun roleName_wrongNameExcludes() {
        val screen = sampleScreen()
        assertTrue(search.findAll(screen, UiQuery(roleName = "Map")).isEmpty())
    }

    // ── Content description ─────────────────────────────────────────

    @Test
    fun contentDescription_query() {
        val screen = sampleScreen()
        val found = search.findFirst(screen, UiQuery(contentDescription = "App logo"))
        assertNotNull(found)
        assertEquals("Logo", found?.text)
    }

    @Test
    fun contentDescription_wrongValueExcludes() {
        val screen = sampleScreen()
        assertNull(search.findFirst(screen, UiQuery(contentDescription = "nope")))
    }

    // ── Boolean filters ─────────────────────────────────────────────

    @Test
    fun clickableFilter_returnsClickableOnly() {
        val screen = sampleScreen()
        val results = search.findAll(screen, UiQuery(isClickable = true))
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.isClickable })
    }

    @Test
    fun editableFilter_findsInput() {
        val screen = sampleScreen()
        assertEquals("secret input", search.findFirst(screen, UiQuery(isEditable = true))?.text)
    }

    @Test
    fun checkedFilter_trueAndFalse() {
        val screen = sampleScreen()
        val checked = search.findAll(screen, UiQuery(isChecked = true)).map { it.text }
        assertTrue(checked.contains("Remember"))
        assertTrue(!checked.contains("Unchecked"))
        val unchecked = search.findAll(screen, UiQuery(isChecked = false)).map { it.text }
        assertTrue(unchecked.contains("Unchecked"))
    }

    @Test
    fun scrollableFilter_findsList() {
        val screen = sampleScreen()
        val scrollables = search.findAll(screen, UiQuery(isScrollable = true))
        assertTrue(scrollables.any { it.role == UiRole.LIST })
    }

    @Test
    fun invisibleNode_excludedByDefault() {
        val screen = sampleScreen()
        assertNull(search.findFirst(screen, UiQuery(textContains = "Hidden")))
        val hidden = search.findFirst(screen, UiQuery(textContains = "Hidden", isVisible = false))
        assertEquals("Hidden", hidden?.text)
    }

    @Test
    fun disabledNode_excludedByDefault() {
        val screen = sampleScreen()
        assertNull(search.findFirst(screen, UiQuery(textContains = "Disabled button")))
        val disabled = search.findFirst(screen, UiQuery(textContains = "Disabled button", isEnabled = false))
        assertEquals("Disabled button", disabled?.text)
    }

    // ── Package / class name ────────────────────────────────────────

    @Test
    fun packageName_filter() {
        val screen = sampleScreen()
        assertEquals("Unique", search.findFirst(screen, UiQuery(packageName = "com.other.app"))?.text)
        assertNull(search.findFirst(screen, UiQuery(text = "Unique", packageName = "com.example.app")))
    }

    @Test
    fun className_containsFilter() {
        val screen = sampleScreen()
        assertEquals("secret input", search.findFirst(screen, UiQuery(className = "EditText"))?.text)
        assertTrue(search.findAll(screen, UiQuery(className = "Button")).any { it.text == "Save" })
    }

    // ── Relative position ───────────────────────────────────────────

    @Test
    fun aboveText_scoresAboveNodeHigher() {
        val screen = sampleScreen()
        val results = search.search(screen, UiQuery(aboveText = "Password label", maxResults = Int.MAX_VALUE))
        val header = results.find { it.node.text == "Header" }
        val input = results.find { it.node.text == "secret input" }
        assertNotNull(header)
        assertNotNull(input)
        assertTrue("Header (above) should outscore Input (below)", header!!.score > input!!.score)
    }

    @Test
    fun belowText_scoresBelowNodeHigher() {
        val screen = sampleScreen()
        val results = search.search(screen, UiQuery(belowText = "Password label", maxResults = Int.MAX_VALUE))
        val input = results.find { it.node.text == "secret input" }
        val header = results.find { it.node.text == "Header" }
        assertNotNull(input)
        assertNotNull(header)
        assertTrue("Input (below) should outscore Header (above)", input!!.score > header!!.score)
    }

    @Test
    fun leftOfText_scoresLeftNodeHigher() {
        val screen = sampleScreen()
        val results = search.search(screen, UiQuery(leftOfText = "Center anchor", isClickable = true, maxResults = Int.MAX_VALUE))
        val left = results.find { it.node.text == "left button" }
        val right = results.find { it.node.text == "right button" }
        assertNotNull(left)
        assertNotNull(right)
        assertTrue("left button should outscore right button", left!!.score > right!!.score)
    }

    @Test
    fun rightOfText_scoresRightNodeHigher() {
        val screen = sampleScreen()
        val results = search.search(screen, UiQuery(rightOfText = "Center anchor", isClickable = true, maxResults = Int.MAX_VALUE))
        val right = results.find { it.node.text == "right button" }
        val left = results.find { it.node.text == "left button" }
        assertNotNull(right)
        assertNotNull(left)
        assertTrue("right button should outscore left button", right!!.score > left!!.score)
    }

    @Test
    fun relativePosition_missingLabelReturnsEmpty() {
        val screen = sampleScreen()
        assertTrue(search.search(screen, UiQuery(aboveText = "does not exist")).isEmpty())
    }

    // ── Coordinate proximity ────────────────────────────────────────

    @Test
    fun nearCenter_atExactCenter_scoresHighest() {
        val screen = sampleScreen()
        // Save center = (200, 435)
        val results = search.search(screen, UiQuery(nearCenterX = 200, nearCenterY = 435, maxResults = Int.MAX_VALUE))
        val save = results.find { it.node.text == "Save" }
        val cancel = results.find { it.node.text == "Cancel" }
        assertNotNull(save)
        assertNotNull(cancel)
        assertTrue("Save (dist 0) should outscore Cancel (dist ~300)", save!!.score > cancel!!.score)
    }

    @Test
    fun nearCenter_farAwayNoBonus() {
        val screen = sampleScreen()
        // Probe point (1000, 100) is > 300px from every node center.
        val results = search.search(screen, UiQuery(nearCenterX = 1000, nearCenterY = 100, maxResults = Int.MAX_VALUE))
        // No proximity bonus -> base score only.
        assertTrue(results.all { it.score <= 0.5f + 1e-6f })
    }

    // ── Semantic similarity ─────────────────────────────────────────

    @Test
    fun semanticSimilar_wordOverlapRanksHigher() {
        val screen = sampleScreen()
        val results = search.search(screen, UiQuery(semanticSimilarTo = "save"))
        assertTrue(results.isNotEmpty())
        assertEquals("Save", results.first().node.text)
    }

    @Test
    fun semanticSimilar_partialOverlapStillMatches() {
        val screen = sampleScreen()
        val results = search.search(screen, UiQuery(semanticSimilarTo = "row", maxResults = Int.MAX_VALUE))
        assertTrue(results.any { it.node.text == "Row 1" })
        assertTrue(results.any { it.node.text == "Row 2" })
    }

    // ── Result limiting & helpers ───────────────────────────────────

    @Test
    fun maxResults_limitsResults() {
        val screen = sampleScreen()
        val limited = search.search(screen, UiQuery(role = UiRole.BUTTON, maxResults = 2))
        assertEquals(2, limited.size)
        val all = search.findAll(screen, UiQuery(role = UiRole.BUTTON, isEnabled = null))
        assertTrue(all.size >= 6)
    }

    @Test
    fun findButton_helper_prefersClickable() {
        val screen = sampleScreen()
        assertEquals("Save", search.findButton(screen, "Save")?.text)
        assertNull(search.findButton(screen, "Header"))
    }

    @Test
    fun findTextFields_helper() {
        val screen = sampleScreen()
        assertTrue(search.findTextFields(screen).any { it.text == "secret input" })
    }

    @Test
    fun emptyScreen_returnsNoResults() {
        assertTrue(search.search(ScreenState(), UiQuery(textContains = "anything")).isEmpty())
        assertNull(search.findFirst(ScreenState(), UiQuery(role = UiRole.BUTTON)))
    }

    // ── Strategy descriptors ────────────────────────────────────────

    @Test
    fun matchStrategy_isReportedPerQueryType() {
        val screen = sampleScreen()
        assertEquals("exact_text", search.search(screen, UiQuery(textExact = "Save"))[0].matchStrategy)
        assertEquals("text", search.search(screen, UiQuery(text = "Save"))[0].matchStrategy)
        assertEquals("text_contains", search.search(screen, UiQuery(textContains = "av"))[0].matchStrategy)
        assertEquals("role", search.search(screen, UiQuery(role = UiRole.BUTTON))[0].matchStrategy)
        assertEquals("content_desc", search.search(screen, UiQuery(contentDescription = "App logo"))[0].matchStrategy)
        assertEquals("coordinates", search.search(screen, UiQuery(nearCenterX = 200, nearCenterY = 435))[0].matchStrategy)
    }
}
