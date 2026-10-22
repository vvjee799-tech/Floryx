package com.flowchat.app.presentation.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenContractTest {
    @Test
    fun appSettingsConsolidatesLifeToolsIntoDeviceAssistant() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val drawerStart = source.indexOf("ModalDrawerSheet")
        val scaffoldStart = source.indexOf("Scaffold(", drawerStart)
        val drawerBlock = source.substring(drawerStart, scaffoldStart)
        val settingsStart = source.indexOf("internal fun AppSettingsSheet(")
        val settingsEnd = source.indexOf("internal fun DeviceAssistantDialog(", settingsStart)
        val settingsBlock = source.substring(settingsStart, settingsEnd)
        val deviceAssistantStart = settingsEnd
        val deviceAssistantEnd = source.indexOf(
            "private fun DeviceActionConfirmationDialog(",
            deviceAssistantStart
        )
        val deviceAssistantBlock = source.substring(deviceAssistantStart, deviceAssistantEnd)

        assertFalse(drawerBlock.contains("R.string.providers"))
        assertFalse(drawerBlock.contains("onOpenProviders()"))
        assertTrue(settingsBlock.contains("R.string.model_provider_configuration"))
        assertTrue(settingsBlock.contains("R.string.device_assistant"))
        assertFalse(settingsBlock.contains("R.string.life_tools_permissions"))
        assertTrue(settingsBlock.contains("R.string.memory"))
        assertTrue(settingsBlock.contains("R.string.language"))
        assertTrue(settingsBlock.contains("R.string.appearance"))
        assertTrue(settingsBlock.contains("BuildConfig.VERSION_NAME"))
        assertFalse(source.contains("showLifeToolsDialog"))
        assertFalse(source.contains("private fun LifeToolsDialog("))
        assertTrue(deviceAssistantBlock.contains("onPowerModeEnabledChange: (Boolean) -> Unit"))
        assertTrue(deviceAssistantBlock.contains("appSettings.powerModeEnabled"))
        assertFalse(deviceAssistantBlock.contains("onAppUsageToolEnabledChange: (Boolean) -> Unit"))
        assertFalse(deviceAssistantBlock.contains("onRecentAppActivityToolEnabledChange: (Boolean) -> Unit"))
        assertFalse(deviceAssistantBlock.contains("onOpenAppToolEnabledChange: (Boolean) -> Unit"))
        assertFalse(deviceAssistantBlock.contains("R.string.life_tools_privacy_note"))
        assertTrue(source.contains("MemorySettingsDialog("))
    }

    @Test
    fun conversationSettingsKeepsSamplingControlsCollapsedByDefault() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val start = source.indexOf("internal fun ConversationSettingsSheet(")
        val end = source.indexOf("private fun AssistantAvatarStrip(", start)
        val sheet = source.substring(start, end)

        assertTrue(sheet.contains("var showAdvancedParameters by remember"))
        assertTrue(sheet.contains("R.string.advanced_parameters"))
        assertTrue(sheet.contains("if (showAdvancedParameters)"))
        assertTrue(sheet.contains("SettingsLabeledSlider("))
        assertTrue(sheet.contains("R.string.max_tokens"))
    }

    @Test
    fun composerProvidesFileAttachmentAndRemovalActions() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val composerStart = source.indexOf("internal fun MessageComposer(")
        val composerEnd = source.indexOf("private fun ModelSwitchButton(", composerStart)
        val composer = source.substring(composerStart, composerEnd)

        assertTrue(composer.contains("trailingIcon ="))
        assertTrue(composer.contains("Icons.Default.Add"))
        assertTrue(composer.contains("R.string.upload_file"))
        assertTrue(composer.contains("pendingAttachment"))
        assertTrue(composer.contains("onClick = onPickAttachment"))
        assertTrue(composer.contains("onClick = onClearAttachment"))
    }

    @Test
    fun topBarDoesNotExposeOverflowModelMenu() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val topBarStart = source.indexOf("topBar = {")
        val bottomBarStart = source.indexOf("bottomBar =", topBarStart)
        val topBarBlock = source.substring(topBarStart, bottomBarStart)

        assertFalse(topBarBlock.contains("MoreVert"))
        assertFalse(topBarBlock.contains("DropdownMenu"))
        assertFalse(topBarBlock.contains("DropdownMenuItem"))
        assertFalse(topBarBlock.contains("showModelMenu"))
    }

    @Test
    fun topBarKeepsConversationSettingsOnTitleAndOffersNewChat() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val topBarStart = source.indexOf("TopAppBar(")
        val topBarEnd = source.indexOf("bottomBar =", topBarStart)
        val topBarBlock = source.substring(topBarStart, topBarEnd)
        val titleStart = topBarBlock.indexOf("title = {")
        val navigationStart = topBarBlock.indexOf("navigationIcon =", titleStart)
        val titleBlock = topBarBlock.substring(titleStart, navigationStart)
        val actionsStart = topBarBlock.indexOf("actions = {", navigationStart)
        val actionsBlock = topBarBlock.substring(actionsStart)

        assertTrue(titleBlock.contains(".clickable("))
        assertTrue(titleBlock.contains("enabled = state.currentConversation != null"))
        assertTrue(titleBlock.contains("showSettings = true"))
        assertTrue(titleBlock.contains("ConversationHeaderTitle("))
        assertTrue(titleBlock.contains("assistantAvatarPath = state.currentConversation?.assistantAvatarPath"))
        assertTrue(titleBlock.contains("assistantName?.takeIf"))
        assertTrue(actionsStart >= 0)
        assertTrue(actionsBlock.contains("IconButton("))
        assertTrue(actionsBlock.contains("viewModel::newConversation"))
        assertTrue(titleBlock.contains("R.string.conversation_settings"))
        assertFalse(topBarBlock.contains("painterResource(R.drawable.reference_gear)"))
        assertFalse(source.contains("private fun ReferenceGearIcon("))
        assertTrue(source.contains("internal fun ConversationHeaderTitle("))
        assertFalse(source.contains("val rows = listOf("))
    }

    @Test
    fun referenceGearPngUsesTransparentBackground() {
        val image = decodeRgbaPng(File("src/main/res/drawable/reference_gear.png"))
        val corners = listOf(
            image.alphaAt(0, 0),
            image.alphaAt(image.width - 1, 0),
            image.alphaAt(0, image.height - 1),
            image.alphaAt(image.width - 1, image.height - 1)
        )

        corners.forEach { alpha ->
            assertEquals(0, alpha)
        }
        assertTrue(
            (0 until image.width).any { x ->
                (0 until image.height).any { y ->
                    image.alphaAt(x, y) > 240
                }
            }
        )
    }

    @Test
    fun launcherIconUsesFourthVersionRasterMipmapWithoutAdaptiveOverride() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val expectedSizes = mapOf(
            "mipmap-mdpi" to 48,
            "mipmap-hdpi" to 72,
            "mipmap-xhdpi" to 96,
            "mipmap-xxhdpi" to 144,
            "mipmap-xxxhdpi" to 192
        )

        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertFalse(File("src/main/res/mipmap-anydpi-v26/ic_launcher.xml").exists())

        expectedSizes.forEach { (directory, expectedSize) ->
            val icon = File("src/main/res/$directory/ic_launcher.png")
            assertTrue("Missing launcher icon at ${icon.path}", icon.exists())
            val (width, height) = readPngDimensions(icon)
            assertEquals(expectedSize, width)
            assertEquals(expectedSize, height)
        }

        val xhdpiIcon = decodeRgbaPng(File("src/main/res/mipmap-xhdpi/ic_launcher.png"))
        val lightBackgroundPixels = xhdpiIcon.countPixels { _, _, r, g, b, alpha ->
            alpha > 220 && r > 210 && g > 210 && b > 210
        }
        val middleLineDarkPixels = xhdpiIcon.countPixels { _, y, r, g, b, alpha ->
            y > xhdpiIcon.height * 0.43 &&
                y < xhdpiIcon.height * 0.52 &&
                alpha > 220 &&
                r < 70 &&
                g < 70 &&
                b < 70
        }
        val centerCurveDarkPixels = xhdpiIcon.countPixels { x, y, r, g, b, alpha ->
            x > xhdpiIcon.width * 0.43 &&
                x < xhdpiIcon.width * 0.58 &&
                y > xhdpiIcon.height * 0.35 &&
                y < xhdpiIcon.height * 0.68 &&
                alpha > 220 &&
                r < 70 &&
                g < 70 &&
                b < 70
        }
        assertTrue("Fourth version launcher icon should keep a mostly light background", lightBackgroundPixels > 8_000)
        assertTrue("Fourth version launcher icon should keep the black horizontal stroke", middleLineDarkPixels > 80)
        assertTrue("Fourth version launcher icon should keep the black center curve", centerCurveDarkPixels > 70)
    }

    @Test
    fun conversationRowsOpenOnClickAndDeleteOnLongPress() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()

        assertFalse(source.contains("stringResource(R.string.open)"))
        assert(source.contains("combinedClickable("))
        assert(source.contains("onLongClick"))
        assert(source.contains("AlertDialog("))
        assert(source.contains("deleteConversation("))
    }

    @Test
    fun drawerKeepsProfileAndSettingsAccessibleBelowConversationHistory() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val zhStrings = File("src/main/res/values-zh-rCN/strings.xml").readText()
        val drawerStart = source.indexOf("ModalDrawerSheet")
        val scaffoldStart = source.indexOf("Scaffold(", drawerStart)
        val drawerBlock = source.substring(drawerStart, scaffoldStart)
        val profileEntryStart = drawerBlock.indexOf("UserProfileEntry(")
        val conversationsLabelStart = drawerBlock.indexOf("R.string.conversation_history")
        val firstConversationLoopStart = drawerBlock.indexOf("items(state.conversations", conversationsLabelStart)

        assertTrue(profileEntryStart > 0)
        assertTrue(drawerBlock.contains("showUserSettings = true"))
        val drawerHeaderBlock = drawerBlock.substring(drawerBlock.indexOf("FlowWordmark("), profileEntryStart)
        assertFalse(drawerHeaderBlock.contains("IconButton("))
        assertFalse(drawerHeaderBlock.contains("Icons.Default.Settings"))
        assertTrue(drawerBlock.contains("Icons.Outlined.Settings"))
        assertFalse(drawerBlock.contains("R.string.providers"))
        assertFalse(drawerBlock.contains("onOpenProviders()"))
        assertTrue(drawerBlock.contains("R.string.settings"))
        assertTrue(drawerBlock.contains("showAppSettings = true"))
        assertTrue(drawerBlock.contains("R.string.conversation_history"))
        assertFalse(drawerBlock.contains("R.string.edit"))
        assertTrue(conversationsLabelStart < profileEntryStart)
        assertTrue(firstConversationLoopStart > conversationsLabelStart)
        assertFalse(drawerBlock.contains("TextButton(onClick = onOpenProviders"))
        assertFalse(drawerBlock.contains("LanguageSelector(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))"))
        assertTrue(source.contains("internal fun UserProfileSheet("))
        assertTrue(source.contains("internal fun AppSettingsSheet("))
        assertFalse(source.contains("private fun UserProfileSettingsSheet("))
        assertFalse(source.contains("onOpenWebSearch"))
        assertTrue(source.contains("private fun LanguageDialog("))
        assertFalse(source.contains("private fun LanguageSelector("))
        assertTrue(source.contains("onOpenProviders()"))
        assertTrue(strings.contains("<string name=\"user_display_name\">User</string>"))
        assertTrue(strings.contains("<string name=\"conversation_list\">Conversation list</string>"))
        assertTrue(strings.contains("<string name=\"conversation_history\">Conversations</string>"))
        assertTrue(strings.contains("<string name=\"model_provider_configuration\">Model provider configuration</string>"))
        assertTrue(zhStrings.contains("name=\"user_display_name\""))
        assertTrue(zhStrings.contains("name=\"conversation_list\""))
        assertTrue(zhStrings.contains("name=\"conversation_history\""))
        assertTrue(zhStrings.contains("name=\"model_provider_configuration\""))
    }

    @Test
    fun appSettingsProvidesAppearanceAndLanguageDialogs() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val appearanceSource = File("src/main/java/com/flowchat/app/ui/theme/AppAppearance.kt").readText()
        val themeSource = File("src/main/java/com/flowchat/app/ui/theme/Theme.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val zhStrings = File("src/main/res/values-zh-rCN/strings.xml").readText()
        val sheetStart = source.indexOf("internal fun AppSettingsSheet(")
        val sheetEnd = source.indexOf("private fun SettingsRow", sheetStart)
        val sheetBlock = source.substring(sheetStart, sheetEnd)
        val dialogStart = source.indexOf("private fun AppearanceDialog(")
        val dialogEnd = source.indexOf("private fun SettingsSelectionOptionRow(", dialogStart)
        val dialogBlock = source.substring(dialogStart, dialogEnd)
        val dotStart = source.indexOf("private fun AppearanceRadioDot(")
        val dotEnd = source.indexOf("internal fun ConversationSettingsSheet(", dotStart)
        val dotBlock = source.substring(dotStart, dotEnd)
        val languageIndex = sheetBlock.indexOf("R.string.language")
        val appearanceIndex = sheetBlock.indexOf("R.string.appearance")
        val versionIndex = sheetBlock.indexOf("BuildConfig.VERSION_NAME")

        assertTrue(source.contains("appAppearance: AppAppearance"))
        assertTrue(source.contains("onAppAppearanceChange: (AppAppearance) -> Unit"))
        assertTrue(source.contains("appAppearance = appAppearance"))
        assertTrue(source.contains("onAppAppearanceChange = onAppAppearanceChange"))
        assertTrue(appearanceSource.contains("System(\"system\")"))
        assertTrue(themeSource.contains("import androidx.compose.foundation.isSystemInDarkTheme"))
        assertTrue(themeSource.contains("AppAppearance.System -> isSystemInDarkTheme()"))
        assertTrue(sheetBlock.contains("R.string.settings"))
        assertTrue(sheetBlock.contains("var showAppearanceDialog by remember { mutableStateOf(false) }"))
        assertTrue(sheetBlock.contains("var showLanguageDialog by remember { mutableStateOf(false) }"))
        assertTrue(sheetBlock.contains("R.string.language"))
        assertTrue(sheetBlock.contains("trailingText = stringResource(currentLanguage.labelRes())"))
        assertTrue(sheetBlock.contains("showLanguageDialog = true"))
        assertTrue(sheetBlock.contains("LanguageDialog("))
        assertTrue(sheetBlock.contains("selected = currentLanguage"))
        assertFalse(sheetBlock.contains("LanguageSelector("))
        assertTrue(sheetBlock.contains("R.string.appearance"))
        assertTrue(sheetBlock.contains("trailingText = stringResource(appAppearance.labelRes())"))
        assertTrue(sheetBlock.contains("showAppearanceDialog = true"))
        assertTrue(sheetBlock.contains("BuildConfig.VERSION_NAME"))
        assertTrue(sheetBlock.contains("BuildConfig.VERSION_NAME"))
        assertTrue(languageIndex >= 0)
        assertTrue(appearanceIndex >= 0)
        assertTrue(versionIndex > appearanceIndex)
        assertTrue(sheetBlock.contains("AppearanceDialog("))
        assertTrue(sheetBlock.contains("selected = appAppearance"))
        assertTrue(sheetBlock.contains("onSelect = onAppAppearanceChange"))
        assertTrue(dialogBlock.contains("Dialog(onDismissRequest = onDismiss)"))
        assertTrue(dialogBlock.contains("SettingsSelectionOptionRow("))
        assertTrue(dialogBlock.contains("label = stringResource(R.string.appearance_system)"))
        assertTrue(dialogBlock.contains("label = stringResource(R.string.appearance_light)"))
        assertTrue(dialogBlock.contains("label = stringResource(R.string.appearance_dark)"))
        val languageDialogStart = source.indexOf("private fun LanguageDialog(")
        val languageDialogEnd = source.indexOf("private fun SettingsSelectionOptionRow(", languageDialogStart)
        val languageDialogBlock = source.substring(languageDialogStart, languageDialogEnd)
        assertTrue(languageDialogBlock.contains("Dialog(onDismissRequest = onDismiss)"))
        assertTrue(languageDialogBlock.contains("AppLanguage.System"))
        assertTrue(languageDialogBlock.contains("AppLanguage.ChineseSimplified"))
        assertTrue(languageDialogBlock.contains("AppLanguage.English"))
        assertTrue(languageDialogBlock.contains("SettingsSelectionOptionRow("))
        assertTrue(source.contains("private fun AppearanceRadioDot("))
        assertTrue(dotBlock.contains("drawCircle("))
        assertTrue(dotBlock.contains("style = Stroke(width = 2.5.dp.toPx())"))
        assertTrue(dotBlock.contains("if (selected)"))
        assertTrue(dotBlock.contains("radius = size.minDimension * 0.18f"))
        assertFalse(dotBlock.contains("RadioButton("))
        assertFalse(sheetBlock.contains("R.string.background_settings"))
        assertFalse(sheetBlock.contains("BackgroundModeButton("))
        assertFalse(source.contains("private fun BackgroundModeButton("))
        assertTrue(strings.contains("<string name=\"appearance\">Appearance</string>"))
        assertTrue(strings.contains("<string name=\"appearance_system\">Follow system</string>"))
        assertTrue(strings.contains("<string name=\"appearance_light\">Light</string>"))
        assertTrue(strings.contains("<string name=\"appearance_dark\">Dark</string>"))
        assertTrue(strings.contains("<string name=\"version\">Version</string>"))
        assertTrue(zhStrings.contains("name=\"appearance\""))
        assertTrue(zhStrings.contains("name=\"appearance_system\""))
        assertTrue(zhStrings.contains("name=\"appearance_light\""))
        assertTrue(zhStrings.contains("name=\"appearance_dark\""))
        assertTrue(zhStrings.contains("name=\"version\""))
    }

    @Test
    fun appSettingsDoesNotExposeProviderProfileBackgroundOrUsageAccessEntries() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val zhStrings = File("src/main/res/values-zh-rCN/strings.xml").readText()
        val sheetStart = source.indexOf("internal fun AppSettingsSheet(")
        val sheetEnd = source.indexOf("private fun SettingsRow", sheetStart)
        val sheetBlock = source.substring(sheetStart, sheetEnd)

        assertTrue(manifest.contains("android.permission.PACKAGE_USAGE_STATS"))
        assertTrue(manifest.contains("xmlns:tools=\"http://schemas.android.com/tools\""))
        assertTrue(manifest.contains("tools:ignore=\"ProtectedPermissions\""))
        assertFalse(sheetBlock.contains("usageAccessGranted: Boolean"))
        assertFalse(sheetBlock.contains("onOpenUsageAccessSettings: () -> Unit"))
        assertTrue(sheetBlock.contains("R.string.model_provider_configuration"))
        assertFalse(sheetBlock.contains("R.string.profile_name"))
        assertFalse(sheetBlock.contains("R.string.background_settings"))
        assertTrue(sheetBlock.contains("R.string.device_assistant"))
        assertFalse(sheetBlock.contains("R.string.life_tools_permissions"))
        assertFalse(sheetBlock.contains("R.string.app_usage_access"))
        assertFalse(sheetBlock.contains("R.string.permission_enabled"))
        assertFalse(sheetBlock.contains("R.string.permission_open"))
        assertFalse(strings.contains("name=\"life_tools_permissions\""))
        assertTrue(strings.contains("<string name=\"app_usage_access\">App usage access</string>"))
        assertTrue(strings.contains("<string name=\"permission_enabled\">Enabled</string>"))
        assertTrue(strings.contains("<string name=\"permission_open\">Open</string>"))
        assertFalse(zhStrings.contains("name=\"life_tools_permissions\""))
        assertTrue(zhStrings.contains("name=\"app_usage_access\""))
    }

    @Test
    fun usageAccessPermissionIsRequestedOnlyWhenToolNeedsIt() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val uiStateSource = File("src/main/java/com/flowchat/app/presentation/chat/ChatUiState.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val zhStrings = File("src/main/res/values-zh-rCN/strings.xml").readText()
        val sheetStart = source.indexOf("internal fun AppSettingsSheet(")
        val sheetEnd = source.indexOf("private fun SettingsRow", sheetStart)
        val sheetBlock = source.substring(sheetStart, sheetEnd)

        assertTrue(uiStateSource.contains("val usageAccessPermissionRequest: UsageAccessPermissionRequestUi? = null"))
        assertTrue(source.contains("state.usageAccessPermissionRequest?.let"))
        assertTrue(source.contains("UsageAccessPermissionDialog("))
        assertTrue(source.contains("viewModel.dismissUsageAccessPermissionRequest()"))
        assertTrue(source.contains("Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)"))
        assertTrue(source.contains("private fun UsageAccessPermissionDialog("))
        assertFalse(sheetBlock.contains("Settings.ACTION_USAGE_ACCESS_SETTINGS"))
        assertFalse(sheetBlock.contains("usageAccessPermissionRequest"))
        assertTrue(strings.contains("<string name=\"usage_access_permission_title\">Allow app usage access?</string>"))
        assertTrue(strings.contains("<string name=\"usage_access_permission_message\">"))
        assertTrue(strings.contains("<string name=\"open_system_settings\">Open system settings</string>"))
        assertTrue(zhStrings.contains("name=\"usage_access_permission_title\""))
        assertTrue(zhStrings.contains("name=\"usage_access_permission_message\""))
        assertTrue(zhStrings.contains("name=\"open_system_settings\""))
    }

    @Test
    fun userProfileEntryIsTransparentUntilPressedAndProfileSheetCanEditAvatarAndName() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val zhStrings = File("src/main/res/values-zh-rCN/strings.xml").readText()
        val entryStart = source.indexOf("private fun UserProfileEntry(")
        val sheetStart = source.indexOf("internal fun UserProfileSheet(")
        val entryBlock = source.substring(entryStart, sheetStart)
        val sheetEnd = source.indexOf("internal fun AppSettingsSheet(", sheetStart)
        val sheetBlock = source.substring(sheetStart, sheetEnd)

        assertTrue(entryBlock.contains("avatarPath: String?"))
        assertTrue(entryBlock.contains(".clip(RoundedCornerShape(16.dp))"))
        assertTrue(entryBlock.contains(".combinedClickable("))
        assertFalse(entryBlock.contains("Card("))
        assertFalse(entryBlock.contains(".background("))
        assertFalse(entryBlock.contains("imageVector = OriginalSettingsIcon"))
        assertFalse(entryBlock.contains("R.string.settings"))
        assertFalse(entryBlock.contains("KeyboardArrowRight"))
        assertTrue(sheetBlock.contains("rememberLauncherForActivityResult(ActivityResultContracts.GetContent())"))
        assertTrue(sheetBlock.contains("copyUserAvatarToPrivateFile(context, uri)"))
        assertTrue(sheetBlock.contains("ProfileAvatar("))
        assertTrue(sheetBlock.contains("AvatarEditButton("))
        assertTrue(sheetBlock.contains("OutlinedTextField("))
        assertTrue(sheetBlock.contains("R.string.profile_name"))
        assertTrue(sheetBlock.contains("onSave(profileName, profileAvatarPath)"))
        assertFalse(sheetBlock.contains("LanguageSelector("))
        assertFalse(sheetBlock.contains("R.string.model_provider_configuration"))
        assertFalse(sheetBlock.contains("R.string.background_settings"))
        assertFalse(sheetBlock.contains("R.string.app_usage_access"))
        assertTrue(source.contains("saveUserProfile(context, profileName, profileAvatarPath)"))
        assertTrue(strings.contains("<string name=\"profile_name\">Name</string>"))
        assertTrue(zhStrings.contains("name=\"profile_name\""))
    }

    @Test
    fun appDisplayNameUsesFlowChatWithoutSpaceEverywhere() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val zhStrings = File("src/main/res/values-zh-rCN/strings.xml").readText()

        assertTrue(strings.contains("<string name=\"app_name\">FlowChat</string>"))
        assertTrue(zhStrings.contains("<string name=\"app_name\">FlowChat</string>"))
        assertTrue(manifest.contains("android:label=\"@string/app_name\""))
        assertTrue(source.contains("stringResource(R.string.app_name)"))
        assertFalse(source.contains("\"Flow Chat\""))
        assertFalse(strings.contains(">Flow Chat<"))
        assertFalse(zhStrings.contains(">Flow Chat<"))
    }

    @Test
    fun chatViewModelDoesNotCreateDefaultConversationOnStartup() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt").readText()
        val initStart = source.indexOf("init {")
        val initEnd = source.indexOf("\n    fun", initStart)
        val initBlock = source.substring(initStart, initEnd)

        assertTrue(initStart >= 0)
        assertFalse(initBlock.contains("ensureInitialConversation"))
        assertFalse(initBlock.contains("createConversation"))
        assertFalse(initBlock.contains("deleteEmptyDefaultConversations"))
    }

    @Test
    fun blankAssistantCompletionIsMarkedFailedInsteadOfDisplayedAsDots() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt").readText()
        val screenSource = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()

        assertTrue(source.contains("ReplyPolicy.incompleteResult"))
        assertTrue(source.contains("content.isBlank()"))
        assertTrue(source.contains("if (content.isBlank()) MessageStatus.Failed else MessageStatus.Complete"))
        assertTrue(screenSource.contains("isWaitingForAssistant"))
        assertTrue(screenSource.contains("StreamingJumpingDots("))
        assertFalse(screenSource.contains("MessageStatus.Streaming -> \"...\""))
        assertTrue(screenSource.contains("R.string.empty_response_message"))
    }

    @Test
    fun assistantWaitingPlaceholderUsesSequentialJumpingDots() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val jumpingStart = source.indexOf("private fun StreamingJumpingDots(")
        assertTrue(jumpingStart >= 0)
        val composerStart = source.indexOf("@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)", jumpingStart)
        val jumpingBlock = source.substring(jumpingStart, composerStart)

        assertTrue(jumpingBlock.contains("rememberInfiniteTransition"))
        assertTrue(jumpingBlock.contains("infiniteRepeatable("))
        assertTrue(jumpingBlock.contains("tween(durationMillis = 900"))
        assertTrue(jumpingBlock.contains("phaseOffsets = listOf(0f, 0.18f, 0.36f)"))
        assertTrue(jumpingBlock.contains("val jumpHeight = dotRadius * 2.2f"))
        assertTrue(jumpingBlock.contains("val centers = listOf("))
        assertTrue(jumpingBlock.contains("center.copy(x = center.x - spacing"))
        assertTrue(jumpingBlock.contains("center.copy(x = center.x + spacing"))
        assertTrue(jumpingBlock.contains("drawCircle("))
        assertFalse(jumpingBlock.contains("maxRadius"))
    }

    @Test
    fun composerRequestsFocusAndShowsKeyboardWhenInputIsUsed() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activitySource = File("src/main/java/com/flowchat/app/MainActivity.kt").readText()
        val composerModifierStart = source.indexOf(".focusRequester(focusRequester)")
        val focusChangedStart = source.indexOf(".onFocusChanged", composerModifierStart)
        val pointerInputStart = source.indexOf(".pointerInput", focusChangedStart)
        val focusChangedBlock = source.substring(focusChangedStart, pointerInputStart)

        assertTrue(source.contains("FocusRequester"))
        assertTrue(source.contains("LocalSoftwareKeyboardController"))
        assertTrue(source.contains("InputMethodManager"))
        assertTrue(source.contains("focusRequester(focusRequester)"))
        assertTrue(composerModifierStart >= 0)
        assertTrue(focusChangedStart >= 0)
        assertTrue(pointerInputStart > focusChangedStart)
        assertTrue(source.contains("keyboardController?.show()"))
        assertTrue(source.contains("showSoftInput(view, InputMethodManager.SHOW_FORCED)"))
        assertTrue(source.contains("requestFocusAndShowKeyboard()"))
        assertFalse(focusChangedBlock.contains("showKeyboard()"))
        assertFalse(focusChangedBlock.contains("requestFocus()"))
        assertTrue(manifest.contains("android:windowSoftInputMode=\"adjustResize\""))
        assertTrue(activitySource.contains("SOFT_INPUT_ADJUST_RESIZE"))
    }

    @Test
    fun conversationSettingsKeepsSaveButtonReachableForLongSystemPrompts() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val sheetStart = source.indexOf("internal fun ConversationSettingsSheet(")
        val sheetEnd = source.indexOf("private fun Conversation.displayTitle", sheetStart)
        val sheetBlock = source.substring(sheetStart, sheetEnd)
        val promptFieldStart = sheetBlock.indexOf("label = { Text(stringResource(R.string.system_prompt)) }")
        val sliderStart = sheetBlock.indexOf("SettingsLabeledSlider(", promptFieldStart)
        val promptFieldBlock = sheetBlock.substring(promptFieldStart, sliderStart)

        assertTrue(sheetBlock.contains(".heightIn(max = 720.dp)"))
        assertTrue(sheetBlock.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(sheetBlock.contains(".weight(1f)"))
        assertFalse(sheetBlock.contains(".weight(1f, fill = false)"))
        assertTrue(promptFieldBlock.contains("maxLines = 8"))
        assertTrue(sheetBlock.contains(".navigationBarsPadding()"))
        assertTrue(sheetBlock.contains(".imePadding()"))
        assertTrue(sheetBlock.indexOf("Button(") > sheetBlock.indexOf(".verticalScroll(rememberScrollState())"))
    }

    @Test
    fun conversationModelAndEntityStoreAssistantProfile() {
        val modelSource = File("src/main/java/com/flowchat/app/domain/model/Conversation.kt").readText()
        val entitySource = File("src/main/java/com/flowchat/app/data/db/ConversationEntity.kt").readText()

        assertTrue(modelSource.contains("val assistantName: String = \"\""))
        assertTrue(modelSource.contains("val assistantAvatarPath: String? = null"))
        assertTrue(entitySource.contains("val assistantName: String"))
        assertTrue(entitySource.contains("val assistantAvatarPath: String?"))
        assertTrue(entitySource.contains("assistantName = assistantName"))
        assertTrue(entitySource.contains("assistantAvatarPath = assistantAvatarPath"))
    }

    @Test
    fun databaseMigratesAssistantProfileColumnsWithoutDestructiveMigration() {
        val databaseSource = File("src/main/java/com/flowchat/app/data/db/FlowChatDatabase.kt").readText()
        val appModuleSource = File("src/main/java/com/flowchat/app/di/AppModule.kt").readText()

        assertTrue(databaseSource.contains("version = 9"))
        assertTrue(databaseSource.contains("MIGRATION_1_2"))
        assertTrue(databaseSource.contains("ALTER TABLE conversations ADD COLUMN assistantName TEXT NOT NULL DEFAULT ''"))
        assertTrue(databaseSource.contains("ALTER TABLE conversations ADD COLUMN assistantAvatarPath TEXT"))
        assertTrue(databaseSource.contains("MIGRATION_2_3"))
        assertTrue(appModuleSource.contains(".addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)"))
        assertFalse(appModuleSource.contains("fallbackToDestructiveMigration"))
    }

    @Test
    fun conversationSettingsIncludesAssistantAvatarPickerAndNickname() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val sheetStart = source.indexOf("internal fun ConversationSettingsSheet(")
        val sheetEnd = source.indexOf("private fun Conversation.displayTitle", sheetStart)
        val sheetBlock = source.substring(sheetStart, sheetEnd)

        assertTrue(source.contains("rememberLauncherForActivityResult(ActivityResultContracts.GetContent())"))
        assertTrue(source.contains("copyAssistantAvatarToPrivateFile(context, conversation.id, uri)"))
        assertTrue(sheetBlock.contains("AssistantAvatarStrip("))
        assertTrue(sheetBlock.contains("assistantName"))
        assertTrue(sheetBlock.contains("assistantAvatarPath"))
        assertTrue(sheetBlock.contains("R.string.assistant_name"))
        assertTrue(sheetBlock.contains("onSave(assistantName, assistantAvatarPath, showAvatars, prompt"))
        assertTrue(sheetBlock.indexOf("AssistantAvatarStrip(") < sheetBlock.indexOf("R.string.system_prompt"))
        val stripStart = source.indexOf("private fun AssistantAvatarStrip(")
        val stripEnd = source.indexOf("private fun SettingsLabeledSlider(", stripStart)
        val stripBlock = source.substring(stripStart, stripEnd)
        assertFalse(stripBlock.contains("repeat("))
        assertFalse(stripBlock.contains(".horizontalScroll("))
        assertFalse(stripBlock.contains("text = \"AI\""))
    }

    @Test
    fun chatScreenUsesAssistantProfileForTitleAndAssistantMessages() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val titleStart = source.indexOf("TopAppBar(")
        val titleEnd = source.indexOf("navigationIcon =", titleStart)
        val titleBlock = source.substring(titleStart, titleEnd)
        val bubbleStart = source.indexOf("private fun MessageBubble(")
        val bubbleEnd = source.indexOf("@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)", bubbleStart)
        val bubbleBlock = source.substring(bubbleStart, bubbleEnd)

        assertTrue(titleBlock.contains("assistantName?.takeIf"))
        assertTrue(source.contains("MessageList(messages = state.messages, assistantAvatarPath = state.currentConversation?.assistantAvatarPath"))
        assertTrue(source.contains("private fun MessageBubble(message: Message, assistantAvatarPath: String?, userAvatarPath: String?, showAvatars: Boolean, reasoning: Message?)"))
        assertTrue(bubbleBlock.contains("AssistantAvatar("))
        assertTrue(bubbleBlock.contains("if (isUser)"))
        assertTrue(bubbleBlock.contains("Column("))
        assertTrue(source.contains("private fun Conversation.displayAssistantTitle()"))
    }

    @Test
    fun conversationSettingsControlsMessageAvatarsForBothRoles() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val modelSource = File("src/main/java/com/flowchat/app/domain/model/Conversation.kt").readText()
        val entitySource = File("src/main/java/com/flowchat/app/data/db/ConversationEntity.kt").readText()
        val databaseSource = File("src/main/java/com/flowchat/app/data/db/FlowChatDatabase.kt").readText()
        val appModuleSource = File("src/main/java/com/flowchat/app/di/AppModule.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val zhStrings = File("src/main/res/values-zh-rCN/strings.xml").readText()
        val sheetStart = source.indexOf("internal fun ConversationSettingsSheet(")
        val sheetEnd = source.indexOf("private fun AssistantProfileEditor", sheetStart)
        val sheetBlock = source.substring(sheetStart, sheetEnd)
        val bubbleStart = source.indexOf("private fun MessageBubble(")
        val bubbleEnd = source.indexOf("private fun StreamingJumpingDots", bubbleStart)
        val bubbleBlock = source.substring(bubbleStart, bubbleEnd)

        assertTrue(modelSource.contains("val showAvatars: Boolean = false"))
        assertTrue(entitySource.contains("val showAvatars: Boolean"))
        assertTrue(entitySource.contains("showAvatars = showAvatars"))
        assertTrue(databaseSource.contains("version = 9"))
        assertTrue(databaseSource.contains("MIGRATION_2_3"))
        assertTrue(databaseSource.contains("ALTER TABLE conversations ADD COLUMN showAvatars INTEGER NOT NULL DEFAULT 0"))
        assertTrue(appModuleSource.contains(".addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)"))
        assertTrue(sheetBlock.contains("var showAvatars by remember(conversation.id) { mutableStateOf(conversation.showAvatars) }"))
        assertTrue(sheetBlock.contains("Switch("))
        assertTrue(sheetBlock.contains("checked = showAvatars"))
        assertTrue(sheetBlock.contains("onCheckedChange = { showAvatars = it }"))
        assertTrue(sheetBlock.contains("R.string.show_avatars"))
        assertTrue(sheetBlock.contains("onSave(assistantName, assistantAvatarPath, showAvatars, prompt"))
        assertTrue(source.contains("MessageList(messages = state.messages, assistantAvatarPath = state.currentConversation?.assistantAvatarPath, userAvatarPath = userAvatarPath, showAvatars = state.currentConversation?.showAvatars == true"))
        assertTrue(source.contains("private fun MessageBubble(message: Message, assistantAvatarPath: String?, userAvatarPath: String?, showAvatars: Boolean, reasoning: Message?)"))
        assertTrue(bubbleBlock.contains("if (showAvatars)"))
        assertTrue(bubbleBlock.contains("if (isUser)"))
        assertTrue(bubbleBlock.contains("ProfileAvatar("))
        assertTrue(bubbleBlock.contains("AssistantAvatar("))
        assertTrue(strings.contains("<string name=\"show_avatars\">Show avatars</string>"))
        assertTrue(zhStrings.contains("name=\"show_avatars\""))
    }

    @Test
    fun composerHasThinkingControlAndMessagesRenderReasoningContent() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val viewModelSource = File("src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt").readText()
        val requestFactorySource = File("src/main/java/com/flowchat/app/domain/chat/ChatRequestFactory.kt").readText()
        val modelSource = File("src/main/java/com/flowchat/app/domain/model/Conversation.kt").readText()
        val entitySource = File("src/main/java/com/flowchat/app/data/db/ConversationEntity.kt").readText()
        val messageSource = File("src/main/java/com/flowchat/app/domain/model/Message.kt").readText()
        val messageEntitySource = File("src/main/java/com/flowchat/app/data/db/MessageEntity.kt").readText()
        val databaseSource = File("src/main/java/com/flowchat/app/data/db/FlowChatDatabase.kt").readText()
        val appModuleSource = File("src/main/java/com/flowchat/app/di/AppModule.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val zhStrings = File("src/main/res/values-zh-rCN/strings.xml").readText()
        val sheetStart = source.indexOf("internal fun ConversationSettingsSheet(")
        val sheetEnd = source.indexOf("private fun AssistantProfileEditor", sheetStart)
        val sheetBlock = source.substring(sheetStart, sheetEnd)
        val bubbleStart = source.indexOf("private fun MessageBubble(")
        val bubbleEnd = source.indexOf("private fun StreamingJumpingDots", bubbleStart)
        val bubbleBlock = source.substring(bubbleStart, bubbleEnd)
        val bottomBarStart = source.indexOf("bottomBar = {")
        val bottomBarEnd = source.indexOf(") { padding ->", bottomBarStart)
        val bottomBarBlock = source.substring(bottomBarStart, bottomBarEnd)
        val composerStart = source.indexOf("internal fun MessageComposer(")
        val composerEnd = source.indexOf("@OptIn(ExperimentalMaterial3Api::class)", composerStart)
        val composerBlock = source.substring(composerStart, composerEnd)

        assertTrue(modelSource.contains("val enableThinking: Boolean = true"))
        assertTrue(entitySource.contains("val enableThinking: Boolean"))
        assertTrue(entitySource.contains("enableThinking = enableThinking"))
        assertTrue(messageSource.contains("val reasoningContent: String = \"\""))
        assertTrue(messageSource.contains("val reasoningDurationMillis: Long = 0L"))
        assertTrue(messageEntitySource.contains("val reasoningContent: String"))
        assertTrue(messageEntitySource.contains("val reasoningDurationMillis: Long"))
        assertTrue(messageEntitySource.contains("reasoningContent = reasoningContent"))
        assertTrue(messageEntitySource.contains("reasoningDurationMillis = reasoningDurationMillis"))
        assertTrue(databaseSource.contains("version = 9"))
        assertTrue(databaseSource.contains("MIGRATION_3_4"))
        assertTrue(databaseSource.contains("ALTER TABLE conversations ADD COLUMN enableThinking INTEGER NOT NULL DEFAULT 0"))
        assertTrue(databaseSource.contains("ALTER TABLE messages ADD COLUMN reasoningContent TEXT NOT NULL DEFAULT ''"))
        assertTrue(databaseSource.contains("MIGRATION_4_5"))
        assertTrue(databaseSource.contains("ALTER TABLE messages ADD COLUMN reasoningDurationMillis INTEGER NOT NULL DEFAULT 0"))
        assertTrue(appModuleSource.contains(".addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)"))
        assertFalse(sheetBlock.contains("var enableThinking"))
        assertFalse(sheetBlock.contains("R.string.deep_thinking"))
        assertFalse(sheetBlock.contains("checked = enableThinking"))
        assertTrue(composerBlock.contains("ThinkingModeControl("))
        assertTrue(sheetBlock.contains("onSave(assistantName, assistantAvatarPath, showAvatars, prompt"))
        assertFalse(bottomBarBlock.contains("thinkingEnabled ="))
        assertFalse(bottomBarBlock.contains("onToggleThinking"))
        assertFalse(composerBlock.contains("thinkingEnabled: Boolean"))
        assertFalse(composerBlock.contains("onToggleThinking: () -> Unit"))
        assertFalse(composerBlock.contains("R.string.deep_thinking"))
        assertFalse(composerBlock.contains("onClick = onToggleThinking"))
        assertFalse(composerBlock.contains("if (thinkingEnabled)"))
        assertFalse(composerBlock.contains("DeepThinkingIcon("))
        assertFalse(composerBlock.contains("Icons.Filled.Psychology"))
        assertFalse(source.contains("private fun DeepThinkingIcon("))
        assertFalse(viewModelSource.contains("fun toggleThinking()"))
        assertTrue(requestFactorySource.contains("enableThinking = conversation.enableThinking"))
        assertTrue(bubbleBlock.contains("message.reasoningContent"))
        assertTrue(bubbleBlock.contains("ReasoningBubble("))
        assertTrue(bubbleBlock.contains("val hasReasoning = !isUser && reasoning != null"))
        assertTrue(bubbleBlock.contains("val isWaitingForAssistant ="))
        assertTrue(bubbleBlock.contains("message.content.isBlank() && !hasReasoning"))
        assertTrue(bubbleBlock.contains("val shouldShowContentBubble ="))
        assertTrue(bubbleBlock.contains("if (shouldShowContentBubble)"))
        assertTrue(source.contains("private fun ReasoningBubble("))
        assertTrue(source.contains("R.string.thinking_in_progress"))
        assertTrue(source.contains("R.string.thinking_done"))
        assertTrue(source.contains("R.string.thinking_stopped"))
        assertTrue(source.contains("message.status == MessageStatus.Stopped"))
        assertTrue(source.contains("R.string.thinking_expand"))
        assertTrue(source.contains("R.string.thinking_collapse"))
        assertTrue(source.contains("val elapsedSeconds ="))
        assertTrue(source.contains("message.reasoningDurationMillis"))
        assertFalse(source.contains("message.updatedAt - message.createdAt"))
        assertTrue(source.contains("clickable { expanded = !expanded }"))
        assertTrue(source.contains("text = message.reasoningContent"))
        assertFalse(source.contains("R.string.thinking_block"))
        assertTrue(strings.contains("<string name=\"thinking_in_progress\">Thinking...</string>"))
        assertTrue(strings.contains("<string name=\"thinking_done\">Thought for %1${'$'}d sec</string>"))
        assertTrue(strings.contains("<string name=\"thinking_expand\">Expand</string>"))
        assertTrue(strings.contains("<string name=\"thinking_collapse\">Collapse</string>"))
        assertTrue(zhStrings.contains("name=\"thinking_in_progress\""))
        assertTrue(zhStrings.contains("name=\"thinking_done\""))
        assertTrue(zhStrings.contains("name=\"thinking_expand\""))
        assertTrue(zhStrings.contains("name=\"thinking_collapse\""))
        assertFalse(strings.contains("name=\"thinking_block\""))
        assertFalse(zhStrings.contains("name=\"thinking_block\""))
        assertFalse(strings.contains("name=\"deep_thinking\""))
        assertFalse(zhStrings.contains("name=\"deep_thinking\""))
    }

    @Test
    fun avatarEditPencilButtonsSlightlyOverlapAvatarCornerWithGrayFillAndWhitePencil() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val userSheetStart = source.indexOf("internal fun UserProfileSheet(")
        val userSheetEnd = source.indexOf("internal fun AppSettingsSheet(", userSheetStart)
        val userSheetBlock = source.substring(userSheetStart, userSheetEnd)
        val assistantEditorStart = source.indexOf("private fun AssistantProfileEditor(")
        val assistantEditorEnd = source.indexOf("private fun AvatarEditButton", assistantEditorStart)
        val assistantEditorBlock = source.substring(assistantEditorStart, assistantEditorEnd)
        val editButtonStart = source.indexOf("private fun AvatarEditButton(")
        val editButtonEnd = source.indexOf("private fun ProfileAvatar", editButtonStart)
        val editButtonBlock = source.substring(editButtonStart, editButtonEnd)

        assertTrue(userSheetBlock.contains("AvatarEditButton("))
        assertTrue(userSheetBlock.contains(".align(Alignment.BottomEnd)"))
        assertTrue(userSheetBlock.contains(".offset(x = (-4).dp, y = (-4).dp)"))
        assertFalse(userSheetBlock.contains("OutlinedIconButton("))
        assertFalse(userSheetBlock.contains(".size(42.dp)"))
        assertTrue(assistantEditorBlock.contains("AvatarEditButton("))
        assertTrue(assistantEditorBlock.contains(".align(Alignment.BottomEnd)"))
        assertTrue(assistantEditorBlock.contains(".offset(x = (-4).dp, y = (-4).dp)"))
        assertFalse(assistantEditorBlock.contains("OutlinedIconButton("))
        assertFalse(assistantEditorBlock.contains(".size(42.dp)"))
        assertTrue(editButtonBlock.contains(".size(32.dp)"))
        assertTrue(editButtonBlock.contains(".background(Color(0xFF3A3A3A), CircleShape)"))
        assertTrue(editButtonBlock.contains("val pencilColor = Color.White"))
        assertTrue(editButtonBlock.contains("Canvas(modifier = Modifier.size(17.dp))"))
        assertTrue(editButtonBlock.contains("val upperBodyStart = Offset("))
        assertTrue(editButtonBlock.contains("val lowerBodyStart = Offset("))
        assertTrue(editButtonBlock.contains("val pencilTip = Offset("))
        assertTrue(editButtonBlock.contains("val tailStart = upperBodyEnd"))
        assertTrue(editButtonBlock.contains("val tailEnd = lowerBodyEnd"))
        assertTrue(editButtonBlock.contains("start = upperBodyStart"))
        assertTrue(editButtonBlock.contains("start = lowerBodyStart"))
        assertTrue(editButtonBlock.contains("end = pencilTip"))
        assertFalse(editButtonBlock.contains("val bodyStart = Offset("))
        assertFalse(editButtonBlock.contains("Icons.Default.Edit"))
    }

    @Test
    fun messageBubbleSupportsCopyAndTextSelectionActions() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val zhStrings = File("src/main/res/values-zh-rCN/strings.xml").readText()
        val bubbleStart = source.indexOf("private fun MessageBubble(")
        val bubbleEnd = source.indexOf("private fun StreamingJumpingDots", bubbleStart)
        val bubbleBlock = source.substring(bubbleStart, bubbleEnd)

        assertTrue(bubbleBlock.contains("combinedClickable("))
        assertTrue(bubbleBlock.contains("onLongClick = {"))
        assertTrue(bubbleBlock.contains("showMessageActions = true"))
        assertTrue(bubbleBlock.contains("DropdownMenu("))
        assertTrue(bubbleBlock.contains("DropdownMenuItem("))
        assertTrue(bubbleBlock.contains("clipboardManager.setText(AnnotatedString(copyText))"))
        assertTrue(bubbleBlock.contains("showTextSelection = true"))
        assertTrue(bubbleBlock.contains("SelectionContainer"))
        assertTrue(bubbleBlock.contains("R.string.copy_message"))
        assertTrue(bubbleBlock.contains("R.string.select_text"))
        assertTrue(strings.contains("<string name=\"copy_message\">Copy</string>"))
        assertTrue(strings.contains("<string name=\"select_text\">Select text</string>"))
        assertTrue(zhStrings.contains("<string name=\"copy_message\">复制</string>"))
        assertTrue(zhStrings.contains("<string name=\"select_text\">选取文字</string>"))
    }
    @Test
    fun paperMessagesUseTransparentSurfacesAndUserUnderline() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val bubbleStart = source.indexOf("private fun MessageBubble(")
        val bubbleEnd = source.indexOf("private fun StreamingJumpingDots", bubbleStart)
        val bubbleBlock = source.substring(bubbleStart, bubbleEnd)

        assertTrue(bubbleBlock.contains("val bubbleColor = Color.Transparent"))
        assertTrue(bubbleBlock.contains("if (isUser) drawLine(messageRule"))
        assertTrue(bubbleBlock.contains("MaterialTheme.colorScheme.onBackground"))
        assertFalse(bubbleBlock.contains("MaterialTheme.colorScheme.surfaceVariant\n    }"))
        assertTrue(bubbleBlock.contains("containerColor = bubbleColor"))
        assertTrue(bubbleBlock.contains("contentColor = bubbleContentColor"))
    }

    @Test
    fun messageTimeRemainsAvailableInsideMessageActions() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val bubbleStart = source.indexOf("private fun MessageBubble(")
        val bubbleEnd = source.indexOf("private fun StreamingJumpingDots", bubbleStart)
        val bubbleBlock = source.substring(bubbleStart, bubbleEnd)

        assertTrue(bubbleBlock.contains("val context = LocalContext.current"))
        assertTrue(bubbleBlock.contains("val messageTime = remember(context, message.createdAt)"))
        assertTrue(bubbleBlock.contains("formatMessageTime(context, message.createdAt)"))
        assertTrue(bubbleBlock.contains("text = messageTime"))
        assertTrue(bubbleBlock.contains("style = MaterialTheme.typography.labelSmall"))
        assertTrue(bubbleBlock.contains("color = MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(bubbleBlock.indexOf("Text(messageTime,") > bubbleBlock.indexOf("expanded = showMessageActions"))
        assertTrue(source.contains("private fun formatMessageTime(context: Context, timestampMillis: Long): String"))
        assertTrue(source.contains("android.text.format.DateFormat.getTimeFormat(context)"))
        assertTrue(source.contains("Date(timestampMillis)"))
    }

    @Test
    fun messageListAnimatesOnlyForNewMessagesAndSnapsForStreamingUpdates() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val listStart = source.indexOf("internal fun MessageList(")
        val listEnd = source.indexOf("@OptIn(ExperimentalFoundationApi::class)", listStart)
        val listBlock = source.substring(listStart, listEnd)

        assertTrue(source.contains("import androidx.compose.runtime.LaunchedEffect"))
        assertTrue(source.contains("import androidx.compose.foundation.lazy.rememberLazyListState"))
        assertTrue(listBlock.contains("val listState = rememberLazyListState()"))
        assertTrue(listBlock.contains("groupChatMessages(messages)"))
        assertTrue(listBlock.contains("val lastMessage = displayMessages.lastOrNull()?.message"))
        assertTrue(listBlock.contains("LaunchedEffect(displayMessages.size, lastMessage?.id)"))
        assertTrue(listBlock.contains("listState.animateScrollToItem(displayMessages.size)"))
        assertTrue(listBlock.contains("LaunchedEffect(lastMessage?.content, activeReasoning?.reasoningContent)"))
        assertTrue(listBlock.contains("listState.scrollToItem(displayMessages.size)"))
        assertFalse(listBlock.contains("LaunchedEffect(messages.size, lastMessage?.id, lastMessage?.content, lastMessage?.reasoningContent)"))
        assertTrue(listBlock.contains("state = listState"))
        assertTrue(listBlock.contains("item(key = BottomMessageAnchorKey)"))
        assertTrue(source.contains("private const val BottomMessageAnchorKey = \"bottom-message-anchor\""))
    }

    @Test
    fun composerKeepsThinkingByAttachmentAndModelPickerBelowInput() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val bottomBarStart = source.indexOf("bottomBar = {")
        val bottomBarEnd = source.indexOf(") { padding ->", bottomBarStart)
        val bottomBarBlock = source.substring(bottomBarStart, bottomBarEnd)
        val composerStart = source.indexOf("internal fun MessageComposer(")
        val composerEnd = source.indexOf("@OptIn(ExperimentalMaterial3Api::class)", composerStart)
        val composerBlock = source.substring(composerStart, composerEnd)
        val modelSwitchStart = source.indexOf("private fun ModelSwitchButton(")
        val modelSwitchEnd = source.indexOf("private fun configuredModelOptions(", modelSwitchStart)
        val modelSwitchBlock = source.substring(modelSwitchStart, modelSwitchEnd)
        val optionsStart = source.indexOf("private fun configuredModelOptions(")
        val optionsEnd = source.indexOf("private data class ComposerModelOption", optionsStart)
        val optionsBlock = source.substring(optionsStart, optionsEnd)
        val strings = File("src/main/res/values/strings.xml").readText()
        val zhStrings = File("src/main/res/values-zh-rCN/strings.xml").readText()

        assertTrue(bottomBarBlock.contains("providers = state.providers"))
        assertTrue(bottomBarBlock.contains("currentConversation = state.currentConversation"))
        assertTrue(bottomBarBlock.contains("onSelectModel = viewModel::updateConversationModel"))
        assertTrue(composerBlock.contains("providers: List<ProviderConfig>"))
        assertTrue(composerBlock.contains("currentConversation: Conversation?"))
        assertTrue(composerBlock.contains("onSelectModel: (ProviderConfig, String) -> Unit"))
        assertTrue(composerBlock.contains("val modelOptions = remember(providers) { configuredModelOptions(providers) }"))
        assertTrue(composerBlock.contains("val currentProvider = providers.firstOrNull { provider -> provider.id == currentConversation?.providerId }"))
        assertTrue(composerBlock.indexOf("ModelSwitchButton(") > composerBlock.indexOf("ThinkingModeControl("))
        assertTrue(composerBlock.contains("enabled = enabled && !isStreaming && modelOptions.isNotEmpty()"))
        assertTrue(composerBlock.contains("onSelectModel = onSelectModel"))
        assertTrue(source.contains("private val ComposerToolHeight = 48.dp"))
        assertTrue(modelSwitchBlock.contains("var expanded by remember { mutableStateOf(false) }"))
        assertTrue(modelSwitchBlock.contains(".heightIn(min = ComposerToolHeight)"))
        assertFalse(modelSwitchBlock.contains("IconButton("))
        assertTrue(modelSwitchBlock.contains(".clickable(enabled = enabled) { expanded = true }"))
        assertTrue(modelSwitchBlock.contains("DropdownMenu("))
        assertTrue(modelSwitchBlock.contains("DropdownMenuItem("))
        assertTrue(modelSwitchBlock.contains("ProviderLogoImage("))
        assertTrue(modelSwitchBlock.contains("currentModelName?.takeIf"))
        assertTrue(modelSwitchBlock.contains("option.provider.displayName"))
        assertTrue(modelSwitchBlock.contains("option.modelName"))
        assertTrue(modelSwitchBlock.contains("onSelectModel(option.provider, option.modelName)"))
        assertTrue(modelSwitchBlock.contains("R.string.switch_model"))
        assertTrue(optionsBlock.contains("providers.asSequence()"))
        assertTrue(optionsBlock.contains("filter { provider -> provider.isEnabled }"))
        assertTrue(optionsBlock.contains("filter { provider -> provider.baseUrl.isNotBlank() }"))
        assertTrue(optionsBlock.contains("filter { provider -> provider.defaultModel.isNotBlank() }"))
        assertTrue(optionsBlock.contains("flatMap { provider ->"))
        assertTrue(optionsBlock.contains("recognizedProviderModelNames(provider).map { modelName ->"))
        assertTrue(optionsBlock.contains("ComposerModelOption(provider = provider, modelName = modelName)"))
        assertTrue(source.contains("private fun recognizedProviderModelNames(provider: ProviderConfig): List<String>"))
        assertTrue(source.contains("listOf(provider.defaultModel.trim(), \"deepseek-flash\", \"deepseek-v4-pro\", \"deepseek-v4-flash\")"))
        assertTrue(source.contains("distinctBy { modelName -> modelName.lowercase() }"))
        assertTrue(source.contains("private fun providerLogoRes(providerName: String?): Int?"))
        assertTrue(source.contains("val logoRes = providerLogoRes(providerName)"))
        assertTrue(source.contains("Icons.Outlined.Hub"))
        assertTrue(source.contains("R.drawable.provider_openai"))
        assertTrue(source.contains("R.drawable.provider_claude"))
        assertTrue(source.contains("R.drawable.provider_deepseek"))
        assertTrue(source.contains("R.drawable.provider_gemini"))
        assertTrue(source.contains("else -> null"))
        assertTrue(strings.contains("<string name=\"switch_model\">Switch model</string>"))
        assertTrue(zhStrings.contains("name=\"switch_model\""))
    }

    @Test
    fun composerPreservesSendStopAndEmptyInputGuards() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val composer = source.substring(source.indexOf("internal fun MessageComposer("),
            source.indexOf("private fun ModelSwitchButton("))
        assertTrue(composer.contains("val hasInput = value.isNotBlank() || pendingAttachment != null"))
        assertTrue(composer.contains("if (value.isBlank() && pendingAttachment == null) return"))
        assertTrue(composer.contains("onClick = if (isStreaming) onStop else ::submitMessage"))
        assertTrue(composer.contains("enabled = (enabled && hasInput && !isAttachmentLoading) || isStreaming"))
    }

    @Test
    fun composerClearsTextFieldAfterSubmittingMessage() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val composerStart = source.indexOf("internal fun MessageComposer(")
        val composerEnd = source.indexOf("@OptIn(ExperimentalMaterial3Api::class)", composerStart)
        val composerBlock = source.substring(composerStart, composerEnd)

        assertTrue(composerBlock.contains("fun submitMessage()"))
        assertTrue(composerBlock.contains("if (!enabled) return"))
        assertTrue(composerBlock.contains("if (value.isBlank() && pendingAttachment == null) return"))
        assertTrue(composerBlock.contains("onSend()"))
        assertTrue(composerBlock.contains("onValueChange(\"\")"))
        assertTrue(composerBlock.contains("keyboardActions = KeyboardActions(onSend = { submitMessage() })"))
        assertTrue(composerBlock.contains("onClick = if (isStreaming) onStop else ::submitMessage"))
    }

    @Test
    fun chatScreenDisplaysToolCallsAsTimelineMessagesWhenAgentUsesTools() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val uiStateSource = File("src/main/java/com/flowchat/app/presentation/chat/ChatUiState.kt").readText()
        val messageSource = File("src/main/java/com/flowchat/app/domain/model/Message.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val zhStrings = File("src/main/res/values-zh-rCN/strings.xml").readText()
        val bubbleStart = source.indexOf("private fun MessageBubble(")
        val bubbleEnd = source.indexOf("private fun ReasoningBubble", bubbleStart)
        val bubbleBlock = source.substring(bubbleStart, bubbleEnd)

        assertTrue(messageSource.contains("Tool(\"tool\")"))
        assertTrue(uiStateSource.contains("toolCallStatus").not())
        assertTrue(source.contains("state.toolCallStatus").not())
        assertTrue(source.contains("ToolCallStatusCard(").not())
        assertTrue(source.contains("private fun ToolCallBubble("))
        assertTrue(bubbleBlock.contains("if (message.role == MessageRole.Tool)"))
        assertTrue(bubbleBlock.contains("ToolCallBubble(message)"))
        assertTrue(messageSource.contains("Cancelled"))
        assertTrue(source.contains("private fun localizedToolAction("))
        assertTrue(source.contains("MessageStatus.Streaming -> R.string.tool_action_running"))
        assertTrue(source.contains("MessageStatus.Complete -> R.string.tool_action_complete"))
        assertTrue(source.contains("MessageStatus.Cancelled -> R.string.tool_action_cancelled"))
        assertTrue(source.contains("MessageStatus.Failed -> R.string.tool_action_failed"))
        assertTrue(source.contains("MessageStatus.Streaming -> MaterialTheme.colorScheme.primary"))
        assertTrue(source.contains("MessageStatus.Failed -> MaterialTheme.colorScheme.error"))
        assertTrue(strings.contains("name=\"tool_action_running\""))
        assertTrue(strings.contains("name=\"tool_action_complete\""))
        assertTrue(strings.contains("name=\"tool_action_cancelled\""))
        assertTrue(strings.contains("name=\"tool_action_device_status\""))
        assertFalse(strings.contains("Calling tool:"))
        assertFalse(strings.contains("Tool complete:"))
        assertTrue(zhStrings.contains("name=\"tool_action_running\""))
        assertTrue(zhStrings.contains("name=\"tool_action_complete\""))
        assertTrue(zhStrings.contains("name=\"tool_action_cancelled\""))
    }

    @Test
    fun chatViewModelKeepsReasoningSeparateWithoutArtificialTypingDelays() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt").readText()
        val reasoningBranchStart = source.indexOf("is ChatDelta.Reasoning ->")
        val fullResponseBranchStart = source.indexOf("is ChatDelta.FullResponse ->")
        val doneBranchStart = source.indexOf("ChatDelta.Done ->")
        val reasoningBranch = source.substring(reasoningBranchStart, fullResponseBranchStart)
        val fullResponseBranch = source.substring(fullResponseBranchStart, doneBranchStart)

        assertTrue(source.contains("private suspend fun appendReasoningDelta("))
        assertTrue(source.contains("private suspend fun appendContentDelta("))
        assertFalse(source.contains("private fun String.displayChunks()"))
        assertFalse(source.contains("BatchedReasoningToContentPauseMillis"))
        assertFalse(source.contains("IncrementalDisplayDelayMillis"))
        assertTrue(reasoningBranch.contains("appendReasoningDelta("))
        assertFalse(reasoningBranch.contains("reasoningContent += delta.text"))
        assertTrue(fullResponseBranch.indexOf("appendReasoningDelta(") < fullResponseBranch.indexOf("appendContentDelta("))
    }

    @Test
    fun assistantReplyContentExpandsNearScreenWidthInsteadOfFixedLegacyBubbleWidth() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val bubbleStart = source.indexOf("private fun MessageBubble(")
        val bubbleEnd = source.indexOf("if (showTextSelection)", bubbleStart)
        val bubbleBlock = source.substring(bubbleStart, bubbleEnd)

        assertTrue(source.contains("import androidx.compose.ui.platform.LocalConfiguration"))
        assertTrue(bubbleBlock.contains("val screenWidth = LocalConfiguration.current.screenWidthDp.dp"))
        assertTrue(bubbleBlock.contains("val assistantMaxWidth = minOf(FlowLayout.ContentWidth - FlowLayout.Gutter * 2, screenWidth - FlowLayout.Gutter * 2)"))
        assertTrue(bubbleBlock.contains("val bubbleMaxWidth = if (isUser)"))
        assertTrue(bubbleBlock.contains("minOf(480.dp, assistantMaxWidth * .9f - if (showAvatars) 46.dp else 0.dp)"))
        assertTrue(bubbleBlock.contains("assistantMaxWidth"))
        assertTrue(bubbleBlock.contains("if (isUser)"))
        assertTrue(bubbleBlock.contains("Column("))
    }
}

private data class RgbaPng(
    val width: Int,
    val height: Int,
    val pixels: ByteArray
) {
    fun alphaAt(x: Int, y: Int): Int = pixels[((y * width + x) * 4) + 3].toInt() and 0xFF

    fun countPixels(predicate: (x: Int, y: Int, r: Int, g: Int, b: Int, alpha: Int) -> Boolean): Int {
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = (y * width + x) * 4
                val r = pixels[offset].toInt() and 0xFF
                val g = pixels[offset + 1].toInt() and 0xFF
                val b = pixels[offset + 2].toInt() and 0xFF
                val alpha = pixels[offset + 3].toInt() and 0xFF
                if (predicate(x, y, r, g, b, alpha)) {
                    count++
                }
            }
        }
        return count
    }
}

private fun decodeRgbaPng(file: File): RgbaPng {
    val bytes = file.readBytes()
    require(bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))

    var offset = 8
    var width = 0
    var height = 0
    var bitDepth = 0
    var colorType = 0
    val idat = ByteArrayOutputStream()

    while (offset < bytes.size) {
        val length = readPngInt(bytes, offset)
        val type = String(bytes, offset + 4, 4, StandardCharsets.US_ASCII)
        val dataOffset = offset + 8
        when (type) {
            "IHDR" -> {
                width = readPngInt(bytes, dataOffset)
                height = readPngInt(bytes, dataOffset + 4)
                bitDepth = bytes[dataOffset + 8].toInt() and 0xFF
                colorType = bytes[dataOffset + 9].toInt() and 0xFF
            }
            "IDAT" -> idat.write(bytes, dataOffset, length)
            "IEND" -> break
        }
        offset = dataOffset + length + 4
    }

    require(bitDepth == 8 && colorType == 6) {
        "reference_gear.png must be 8-bit RGBA PNG, got bitDepth=$bitDepth colorType=$colorType"
    }

    val inflated = InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).readBytes()
    val stride = width * 4
    val pixels = ByteArray(height * stride)
    var sourceOffset = 0
    var previous = ByteArray(stride)

    repeat(height) { y ->
        val filter = inflated[sourceOffset++].toInt() and 0xFF
        val row = inflated.copyOfRange(sourceOffset, sourceOffset + stride)
        sourceOffset += stride

        for (index in row.indices) {
            val raw = row[index].toInt() and 0xFF
            val left = if (index >= 4) row[index - 4].toInt() and 0xFF else 0
            val up = previous[index].toInt() and 0xFF
            val upLeft = if (index >= 4) previous[index - 4].toInt() and 0xFF else 0
            val value = when (filter) {
                0 -> raw
                1 -> raw + left
                2 -> raw + up
                3 -> raw + ((left + up) / 2)
                4 -> raw + paethPredictor(left, up, upLeft)
                else -> error("Unsupported PNG filter: $filter")
            }
            row[index] = (value and 0xFF).toByte()
        }

        row.copyInto(pixels, y * stride)
        previous = row
    }

    return RgbaPng(width, height, pixels)
}

private fun readPngInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

private fun readPngDimensions(file: File): Pair<Int, Int> {
    val bytes = file.readBytes()
    require(bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
    return readPngInt(bytes, 16) to readPngInt(bytes, 20)
}

private fun paethPredictor(left: Int, up: Int, upLeft: Int): Int {
    val estimate = left + up - upLeft
    val leftDistance = kotlin.math.abs(estimate - left)
    val upDistance = kotlin.math.abs(estimate - up)
    val upLeftDistance = kotlin.math.abs(estimate - upLeft)
    return when {
        leftDistance <= upDistance && leftDistance <= upLeftDistance -> left
        upDistance <= upLeftDistance -> up
        else -> upLeft
    }
}
