package com.flowchat.app.presentation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.flowchat.app.presentation.chat.ChatViewModel
import com.flowchat.app.presentation.chat.AppSettingsRoute
import com.flowchat.app.presentation.chat.ConversationSettingsScreen
import com.flowchat.app.presentation.chat.TerminalSettingsRoute
import com.flowchat.app.presentation.chat.WorkspaceSettingsRoute
import com.flowchat.app.presentation.chat.ChatScreen
import com.flowchat.app.presentation.provider.ProviderSettingsScreen
import com.flowchat.app.ui.theme.AppAppearance

@Composable
fun FlowChatRoot(
    appAppearance: AppAppearance,
    onAppAppearanceChange: (AppAppearance) -> Unit
) {
    val navController = rememberNavController()
    // Keep active replies and the composer alive across settings destinations.
    val chatViewModel: ChatViewModel = hiltViewModel()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        NavHost(
            navController = navController,
            startDestination = Route.Chat,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            composable(Route.Chat) {
                ChatScreen(
                    onOpenProviders = { navController.navigate(Route.Providers) },
                    onOpenAppSettings = { navController.navigate(Route.Settings) },
                    onOpenConversationSettings = { navController.navigate(Route.ConversationSettings) },
                    appAppearance = appAppearance,
                    onAppAppearanceChange = onAppAppearanceChange,
                    viewModel = chatViewModel
                )
            }
            composable(Route.Settings) {
                AppSettingsRoute(
                    viewModel = chatViewModel,
                    appAppearance = appAppearance,
                    onAppAppearanceChange = onAppAppearanceChange,
                    onBack = { navController.popBackStack() },
                    onOpenProviders = { navController.navigate(Route.Providers) },
                    onOpenTerminal = { navController.navigate(Route.Terminal) },
                    onOpenWorkspace = { navController.navigate(Route.Workspace) }
                )
            }
            composable(Route.ConversationSettings) {
                val state by chatViewModel.uiState.collectAsStateWithLifecycle()
                state.currentConversation?.let { conversation ->
                    ConversationSettingsScreen(
                        conversation = conversation,
                        onDismiss = { navController.popBackStack() },
                        onSave = { name, avatar, showAvatars, prompt, temperature, topP, maxTokens ->
                            chatViewModel.updateConversationSettings(name, avatar, showAvatars, prompt, temperature, topP, maxTokens)
                            navController.popBackStack()
                        }
                    )
                }
            }
            composable(Route.Terminal) {
                TerminalSettingsRoute(chatViewModel, onBack = { navController.popBackStack() })
            }
            composable(Route.Workspace) {
                WorkspaceSettingsRoute(chatViewModel, onBack = { navController.popBackStack() })
            }
            composable(Route.Providers) {
                ProviderSettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private object Route {
    const val Chat = "chat"
    const val Providers = "providers"
    const val Settings = "settings"
    const val ConversationSettings = "conversation-settings"
    const val Terminal = "settings/terminal"
    const val Workspace = "settings/workspace"
}
