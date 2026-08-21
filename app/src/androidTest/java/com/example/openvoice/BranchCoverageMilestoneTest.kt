package com.example.openvoice

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openvoice.accessibility.UiRole
import com.example.openvoice.intent.IntentResult
import com.example.openvoice.planner.CostModel
import com.example.openvoice.planner.DynamicReplanner
import com.example.openvoice.planner.PlanStep
import com.example.openvoice.planner.RecoveryStrategy
import com.example.openvoice.planner.UserConfirmationPolicy
import com.example.openvoice.router.CapabilityRouter
import com.example.openvoice.router.Resolution
import com.example.openvoice.util.PermissionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerBranchMilestoneTest {

    private lateinit var costModel: CostModel
    private lateinit var policy: UserConfirmationPolicy
    private lateinit var replanner: DynamicReplanner

    @Before
    fun setup() {
        costModel = CostModel()
        policy = UserConfirmationPolicy()
        replanner = DynamicReplanner(costModel)
    }

    @Test
    fun costModel_exercisesPatternFamiliesAndFallbacks() {
        val native = listOf(
            "open the camera", "launch the camera", "start the camera", "set a timer",
            "create a reminder", "call Alice", "text Alice", "send a message",
            "volume up", "enable wifi", "disable bluetooth", "turn on airplane mode",
            "turn off airplane mode"
        )
        native.forEach { assertTrue(costModel.selectCheapestCapability(it) is PlanStep.NativeAction) }

        val memory = listOf(
            "remember this", "forget that", "what is my name", "what's my favorite color",
            "what are my preferences", "do i have a routine", "my favorite song",
            "my preferred theme", "who is my contact", "where is my meeting", "when did I save it"
        )
        memory.forEach { assertTrue(costModel.selectCheapestCapability(it) is PlanStep.MemoryRetrieval) }

        listOf("read the page", "read text", "content available").forEach {
            assertTrue(costModel.selectCheapestCapability(it) is PlanStep.OcrAction)
        }
        listOf("summarize this", "explain the issue", "what happened", "how does it work", "why did it fail")
            .forEach { assertTrue(costModel.selectCheapestCapability(it) is PlanStep.LmReasoning) }
        assertTrue(costModel.selectCheapestCapability("reason about anything", requiresReasoning = true)
            is PlanStep.LmReasoning)

        listOf("see the screen", "look at this", "describe the view", "inspect the chart", "find the icon")
            .forEach { assertTrue(costModel.selectCheapestCapability(it) is PlanStep.VisionAction) }
        assertTrue(costModel.selectCheapestCapability("an image", requiresVisualAnalysis = true)
            is PlanStep.VisionAction)
        assertTrue(costModel.selectCheapestCapability("unmatched words") is PlanStep.LmReasoning)

        val operatorInputs = listOf(
            "open settings", "launch app", "call home", "text home", "set timer",
            "set alarm", "volume down", "enable sound", "disable sound", "turn on wifi",
            "turn off wifi", "create task"
        )
        operatorInputs.forEach { assertNotNull(costModel.selectCheapestCapability(it)) }

        val alternates = listOf<PlanStep>(
            PlanStep.NativeAction("native", "HELP"),
            PlanStep.AccessibilityAction("a11y", "tap"),
            PlanStep.OcrAction("ocr"),
            PlanStep.MemoryRetrieval("memory", "query"),
            PlanStep.LmReasoning("llm", "prompt"),
            PlanStep.VisionAction("vision"),
            PlanStep.UserConfirmation("confirm", "prompt"),
            PlanStep.PluginAction("plugin", "plugin", "action"),
            PlanStep.Conditional("conditional", "true", emptyList()),
            PlanStep.Loop("loop", emptyList(), emptyList()),
            PlanStep.Wait("wait", 1),
            PlanStep.Retry("retry", "id")
        )
        alternates.forEach { costModel.suggestAlternate(it) }
        assertTrue(costModel.canEscalateTo(0.1f, 0.2f))
        assertFalse(costModel.canEscalateTo(0.2f, 0.1f))
    }

    @Test
    fun confirmationPolicy_exercisesAllSafetyFamilies() {
        val purchase = listOf("buy item", "purchase item", "pay bill", "checkout cart", "order food",
            "subscribe now", "upgrade plan", "spend money", "show cost", "check price")
        purchase.forEach { assertTrue(policy.evaluate(PlanStep.NativeAction(it, "TEST")).requiresConfirmation) }

        val deletion = listOf("delete file", "remove item", "uninstall app", "erase data", "clear cache",
            "destroy account", "wipe device")
        deletion.forEach { assertTrue(policy.evaluate(PlanStep.NativeAction(it, "TEST")).requiresConfirmation) }

        val financial = listOf("transfer funds", "send money", "withdraw cash", "deposit check",
            "invoice customer", "make payment")
        financial.forEach { assertTrue(policy.evaluate(PlanStep.NativeAction(it, "TEST")).requiresConfirmation) }

        val irreversible = listOf("format disk", "factory reset", "overwrite file", "replace certificate",
            "deactivate account", "terminate process")
        irreversible.forEach { assertTrue(policy.evaluate(PlanStep.NativeAction(it, "TEST")).requiresConfirmation) }

        val security = listOf("change password", "login now", "sign in", "authenticate user", "enable 2fa",
            "use 2-factor", "biometric unlock", "grant permission", "revoke permission")
        security.forEach { assertTrue(policy.evaluate(PlanStep.NativeAction(it, "TEST")).requiresConfirmation) }

        val communication = listOf("send note", "message Alice", "text Alice", "email Alice", "reply now",
            "call Alice", "dial Alice", "post update", "tweet update")
        communication.forEach {
            assertFalse(policy.evaluate(PlanStep.NativeAction(it, "TEST")).requiresConfirmation)
        }

        val information = listOf("what time", "who is this", "where am I", "when is it", "why now",
            "how does this work", "read this", "check status", "find item", "show list", "list files",
            "tell me", "give details")
        information.forEach {
            assertFalse(policy.evaluate(PlanStep.NativeAction(it, "TEST")).requiresConfirmation)
        }

        val navigation = listOf("open app", "launch app", "start app", "go home", "navigate back",
            "back", "home", "recents")
        navigation.forEach {
            assertFalse(policy.evaluate(PlanStep.NativeAction(it, "TEST")).requiresConfirmation)
        }

        val utility = listOf("set timer", "timer", "alarm", "volume", "brightness", "wifi", "bluetooth",
            "flashlight", "torch", "silent", "vibrate", "airplane", "rotate", "screenshot", "record")
        utility.forEach {
            assertFalse(policy.evaluate(PlanStep.NativeAction(it, "TEST")).requiresConfirmation)
        }

        assertFalse(policy.evaluate(PlanStep.LmReasoning("think", "prompt")).requiresConfirmation)
        assertFalse(policy.evaluate(PlanStep.VisionAction("analyze", "question")).requiresConfirmation)
        assertFalse(policy.evaluate(PlanStep.MemoryRetrieval("lookup", "query")).requiresConfirmation)
        assertFalse(policy.evaluate(PlanStep.OcrAction("read", "full")).requiresConfirmation)
        assertFalse(policy.evaluate(PlanStep.PluginAction("unknown", "p", "a")).requiresConfirmation)
    }

    @Test
    fun replanner_exercisesFailureAndRecoveryMatrix() {
        val accessibility = PlanStep.AccessibilityAction("tap", "tap ok")
        val ocr = PlanStep.OcrAction("read", "full")
        val vision = PlanStep.VisionAction("see", "question")
        val native = PlanStep.NativeAction("open", "LAUNCH_APP")
        val memory = PlanStep.MemoryRetrieval("remember", "query")
        val plugin = PlanStep.PluginAction("plugin", "id", "action")

        assertEquals(RecoveryStrategy.ESCALATE, replanner.recover(accessibility, "not found", 0).strategy)
        assertEquals(RecoveryStrategy.ESCALATE, replanner.recover(ocr, "unable to find text", 0).strategy)
        assertEquals(RecoveryStrategy.ASK_USER, replanner.recover(vision, "element not found", 0).strategy)
        assertNotNull(replanner.recover(native, "app not found", 0))
        assertEquals(RecoveryStrategy.ESCALATE, replanner.recover(memory, "not found", 0).strategy)
        assertEquals(RecoveryStrategy.ABORT, replanner.recover(plugin, "not found", 0).strategy)

        assertEquals(RecoveryStrategy.ASK_USER, replanner.recover(native, "permission denied", 0).strategy)
        assertEquals(RecoveryStrategy.RETRY_SAME, replanner.recover(accessibility, "timeout", 0).strategy)
        assertEquals(RecoveryStrategy.RETRY_SAME, replanner.recover(accessibility, "timed out", 0).strategy)
        assertEquals(RecoveryStrategy.ESCALATE, replanner.recover(accessibility, "empty response", 0).strategy)
        assertEquals(RecoveryStrategy.ABORT, replanner.recover(vision, "no response", 0).strategy)
        assertNotNull(replanner.recover(native, "generic failure", 0))
        assertNotNull(replanner.recover(native, "generic failure", 3))
        assertNotNull(replanner.recover(native, "retries exceeded", 0))
    }

    @Test
    fun uiRole_fromClassName_exercisesEveryClassifierBranch() {
        val names = listOf(
            null, "android.widget.Button", "android.widget.EditText", "android.widget.TextView",
            "android.widget.ImageView", "android.widget.Switch", "android.widget.CheckBox",
            "android.widget.Checkbox", "android.widget.RadioButton", "android.widget.ListView",
            "androidx.recyclerview.RecyclerView", "android.widget.Spinner", "android.app.Dialog",
            "android.app.Alert", "android.widget.Toolbar", "android.app.ActionBar", "android.widget.Tab",
            "android.view.Menu", "android.widget.ProgressBar", "android.widget.Slider",
            "android.widget.SeekBar", "android.webkit.WebView", "com.google.MapView",
            "android.widget.CardView", "android.widget.Chip", "android.widget.SearchView",
            "android.widget.DatePicker", "android.widget.TimePicker", "com.example.Unknown"
        )
        names.forEach { assertNotNull(UiRole.fromClassName(it)) }
    }
}

@RunWith(AndroidJUnit4::class)
class RouterAndPermissionBranchMilestoneTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun router_exercisesEveryFallbackResolution() {
        val router = CapabilityRouter()
        assertTrue(router.resolve(IntentResult("OPEN_NOTIFICATIONS", 0.9f)) is Resolution.Native)
        assertTrue(router.resolve(IntentResult("NAVIGATE_BACK", 0.9f)) is Resolution.Accessibility)
        assertTrue(router.resolve(IntentResult("NAVIGATE_HOME", 0.9f)) is Resolution.Accessibility)
        assertTrue(router.resolve(IntentResult("SCROLL", 0.9f)) is Resolution.Accessibility)
        assertTrue(router.resolve(IntentResult("SCROLL", 0.9f, mapOf("direction" to "up"))) is Resolution.Accessibility)
        assertTrue(router.resolve(IntentResult("SCREENSHOT", 0.9f)) is Resolution.Accessibility)
        assertTrue(router.resolve(IntentResult("QUERY", 0.9f, mapOf("query" to "hello"))) is Resolution.LocalLm)
        assertTrue(router.resolve(IntentResult("QUERY", 0.9f, mapOf("first" to "value"))) is Resolution.LocalLm)
        assertTrue(router.resolve(IntentResult("QUERY", 0.9f)) is Resolution.LocalLm)
        assertTrue(router.resolve(IntentResult("READ_SCREEN", 0.9f)) is Resolution.Vision)
        assertTrue(router.resolve(IntentResult("UNKNOWN", 0f, requiresClarification = true)) is Resolution.Unsupported)
        assertTrue(router.resolve(IntentResult("UNKNOWN", 0f, requiresClarification = false)) is Resolution.Unsupported)
        assertTrue(router.resolve(IntentResult("UNHANDLED", 0.1f)) is Resolution.Unsupported)
    }

    @Test
    fun permissionManager_exercisesGrantMissingRequestedAndSummaryPaths() {
        val manager = PermissionManager(context)
        val permission = Manifest.permission.READ_CONTACTS
        context.getSharedPreferences("openvoice_permissions", Context.MODE_PRIVATE)
            .edit().remove("requested_$permission").apply()
        assertEquals(PermissionManager.PermissionState.NOT_GRANTED, manager.checkPermission(permission))
        manager.markRequested(permission)
        assertEquals(PermissionManager.PermissionState.PERMANENTLY_DENIED, manager.checkPermission(permission))
        assertFalse(manager.hasAllRequired())
        manager.isAudioGranted()
        assertTrue(manager.getStatusSummary().contains("Missing:"))
        assertFalse(manager.isAccessibilityEnabled())
        assertTrue(manager.checkAllRequired().all { !it.canRequest || it.state != PermissionManager.PermissionState.GRANTED })
    }
}

@RunWith(AndroidJUnit4::class)
class UiNodeLabelBranchMilestoneTest {

    private fun node(text: String?, description: String?, viewId: String?) =
        com.example.openvoice.accessibility.UiNode(
            text = text,
            description = description,
            className = "android.widget.Button",
            packageName = "com.example.app",
            viewId = viewId,
            bounds = android.graphics.Rect(),
            isClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            isCheckable = false,
            isFocusable = false,
            isEnabled = true,
            isPassword = false,
            isVisible = true,
            depth = 0,
            children = emptyList()
        )

    @Test
    fun label_fallsThroughBlankTextDescriptionIdAndRole() {
        assertEquals("Text", node("Text", "Description", "id").label)
        assertEquals("Description", node("", "Description", "id").label)
        assertEquals("id", node("", "", "id").label)
        assertEquals("Button", node("", "", "").label)
    }
}
