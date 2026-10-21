/*
 * Eclipse Cobblemon Launcher - versão modificada não oficial do Zalith Launcher 2
 * Copyright (C) 2026 Eclipse Cobblemon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.eclipse

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.account.isMicrosoftAccount
import com.movtery.zalithlauncher.game.account.isMicrosoftLogging
import com.movtery.zalithlauncher.game.account.localLogin
import com.movtery.zalithlauncher.game.account.microsoftLogin
import com.movtery.zalithlauncher.ui.androidText
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.components.SimpleAlertDialog
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.ui.screens.content.FirstLoginMenu
import com.movtery.zalithlauncher.ui.screens.content.elements.PlayerFace
import com.movtery.zalithlauncher.ui.screens.content.elements.TitleTaskFlowDialog
import com.movtery.zalithlauncher.ui.screens.content.navigateToWeb
import com.movtery.zalithlauncher.utils.file.formatFileSize
import com.movtery.zalithlauncher.viewmodel.ErrorViewModel
import com.movtery.zalithlauncher.viewmodel.EventViewModel
import com.movtery.zalithlauncher.viewmodel.ScreenBackStackViewModel
import com.movtery.zalithlauncher.viewmodel.sendToast

private val TextOnImage = Color.White
private val TextOnImageDim = Color.White.copy(alpha = 0.72f)
private val GlassCard = Color(0xCC12121C)
private val OnlineGreen = Color(0xFF4ADE80)
private val OfflineRed = Color(0xFFF87171)

/**
 * Tela inicial do launcher: entrar (Microsoft ou nick) e jogar no servidor.
 * Substitui a tela inicial do Zalith; o resto do app (configurações, controles, contas) continua igual.
 */
@Composable
fun EclipseHomeScreen(
    backStackViewModel: ScreenBackStackViewModel,
    eventViewModel: EventViewModel,
    submitError: (ErrorViewModel.ThrowableMessage) -> Unit,
    viewModel: EclipseViewModel = viewModel()
) {
    BaseScreen(
        screenKey = NormalNavKey.LauncherMain,
        currentKey = backStackViewModel.mainScreen.currentKey
    ) { _ ->
        val context = LocalContext.current
        val panel by viewModel.panel.collectAsStateWithLifecycle()
        val status by viewModel.status.collectAsStateWithLifecycle()
        val news by viewModel.news.collectAsStateWithLifecycle()
        val preparer by viewModel.preparer.collectAsStateWithLifecycle()
        val mobileDataPrompt by viewModel.mobileDataPrompt.collectAsStateWithLifecycle()
        val account by AccountsManager.currentAccountFlow.collectAsStateWithLifecycle()

        val openLink: (String) -> Unit = { url ->
            eventViewModel.sendEvent(EventViewModel.Event.OpenLink(url))
        }

        val startMicrosoftLogin: () -> Unit = {
            if (!isMicrosoftLogging()) {
                microsoftLogin(
                    context = context,
                    toWeb = { url -> backStackViewModel.mainScreen.backStack.navigateToWeb(url) },
                    backToMain = { backStackViewModel.mainScreen.clearWith(NormalNavKey.LauncherMain) },
                    checkIfInWebScreen = { backStackViewModel.mainScreen.currentKey is NormalNavKey.WebScreen },
                    updateOperation = {},
                    showToast = { text, duration -> eventViewModel.sendToast(text, duration) },
                    submitError = submitError
                )
            }
        }

        val play: () -> Unit = {
            viewModel.play(
                context = context,
                onReady = { version, address ->
                    eventViewModel.sendEvent(EventViewModel.Event.Launch.PlayServer(version, address))
                },
                onError = { th ->
                    submitError(
                        ErrorViewModel.ThrowableMessage(
                            title = androidText(R.string.eclipse_prepare_failed),
                            message = androidText(th.localizedMessage ?: th.message ?: th::class.java.simpleName)
                        )
                    )
                }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.eclipse_background),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.82f),
                                Color.Black.copy(alpha = 0.35f),
                                Color.Black.copy(alpha = 0.78f)
                            )
                        )
                    )
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InfoColumn(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight(),
                    serverName = panel.server.name,
                    status = status,
                    news = news,
                    onRefreshStatus = viewModel::refreshStatus,
                    onOpenLink = openLink
                )

                PlayColumn(
                    modifier = Modifier
                        .weight(0.9f)
                        .fillMaxHeight(),
                    account = account,
                    isPreparing = preparer != null,
                    panel = panel,
                    onMicrosoftLogin = startMicrosoftLogin,
                    onNickLogin = { nick -> localLogin(nick, null) },
                    onSwitchAccount = {
                        backStackViewModel.mainScreen.navigateTo(
                            screenKey = NormalNavKey.AccountManager(FirstLoginMenu.NONE)
                        )
                    },
                    onPlay = play,
                    onOpenLink = openLink
                )
            }
        }

        preparer?.let { current ->
            val tasks by current.tasksFlow.collectAsStateWithLifecycle()
            TitleTaskFlowDialog(
                title = stringResource(R.string.eclipse_preparing_title),
                tasks = tasks,
                onCancel = viewModel::cancel
            )
        }

        mobileDataPrompt?.let { prompt ->
            SimpleAlertDialog(
                title = stringResource(R.string.eclipse_mobile_data_title),
                text = prompt.bytes?.let {
                    stringResource(R.string.eclipse_mobile_data_text_known, formatFileSize(it))
                } ?: stringResource(R.string.eclipse_mobile_data_text_unknown),
                confirmText = stringResource(R.string.eclipse_mobile_data_continue),
                onConfirm = { prompt.answer.complete(true) },
                onDismiss = { prompt.answer.complete(false) }
            )
        }
    }
}

@Composable
private fun InfoColumn(
    serverName: String,
    status: EclipseServerStatus,
    news: List<EclipseNews>,
    onRefreshStatus: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.eclipse_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column {
                Text(
                    text = serverName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextOnImage,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServerStatusText(status)
                    IconButton(
                        modifier = Modifier.size(28.dp),
                        onClick = onRefreshStatus
                    ) {
                        Icon(
                            modifier = Modifier.size(16.dp),
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = null,
                            tint = TextOnImageDim
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = GlassCard, contentColor = TextOnImage),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.eclipse_news_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (news.isEmpty()) {
                    Text(
                        text = stringResource(R.string.eclipse_news_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextOnImageDim
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(news) { item ->
                            NewsItem(item = item, onOpenLink = onOpenLink)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerStatusText(status: EclipseServerStatus) {
    val (text, color) = when (status) {
        is EclipseServerStatus.Checking -> stringResource(R.string.eclipse_status_checking) to TextOnImageDim
        is EclipseServerStatus.Online -> stringResource(
            R.string.eclipse_status_online,
            status.players,
            status.maxPlayers
        ) to OnlineGreen
        is EclipseServerStatus.Offline -> stringResource(R.string.eclipse_status_offline) to OfflineRed
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun NewsItem(item: EclipseNews, onOpenLink: (String) -> Unit) {
    val link = item.link
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(if (link != null) Modifier.clickable { onOpenLink(link) } else Modifier)
            .padding(4.dp)
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        item.body?.let { body ->
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = TextOnImageDim,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        item.date?.let { date ->
            Text(
                text = date.take(10),
                style = MaterialTheme.typography.labelSmall,
                color = TextOnImageDim
            )
        }
    }
}

@Composable
private fun PlayColumn(
    account: Account?,
    isPreparing: Boolean,
    panel: EclipsePanel,
    onMicrosoftLogin: () -> Unit,
    onNickLogin: (String) -> Unit,
    onSwitchAccount: () -> Unit,
    onPlay: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = GlassCard, contentColor = TextOnImage),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            if (account == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LoginContent(onMicrosoftLogin = onMicrosoftLogin, onNickLogin = onNickLogin)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AccountContent(account = account, onSwitchAccount = onSwitchAccount)
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        enabled = !isPreparing,
                        shape = MaterialTheme.shapes.large,
                        onClick = onPlay
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_play_arrow_filled),
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = stringResource(R.string.eclipse_play),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.eclipse_play_hint, panel.server.address),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextOnImageDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOfNotNull(
                panel.links.discord?.let { stringResource(R.string.eclipse_link_discord) to it },
                panel.links.store?.let { stringResource(R.string.eclipse_link_store) to it },
                panel.links.website?.let { stringResource(R.string.eclipse_link_website) to it }
            ).forEach { (label, url) ->
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextOnImage),
                    onClick = { onOpenLink(url) }
                ) {
                    Text(text = label, maxLines = 1)
                }
            }
        }

        //Aviso exigido pela licença do Zalith Launcher 2 (GPL-3.0, seção 7)
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenLink(EclipseConfig.SOURCE_URL) },
            text = stringResource(R.string.eclipse_unofficial_notice),
            style = MaterialTheme.typography.labelSmall,
            color = TextOnImageDim,
            maxLines = 2
        )
    }
}

@Composable
private fun LoginContent(
    onMicrosoftLogin: () -> Unit,
    onNickLogin: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var nick by rememberSaveable { mutableStateOf("") }
    val nickValid = EclipseConfig.NICK_REGEX.matches(nick)

    fun submitNick() {
        if (nickValid) {
            focusManager.clearFocus()
            onNickLogin(nick)
        }
    }

    Text(
        text = stringResource(R.string.eclipse_login_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )

    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onMicrosoftLogin
    ) {
        Icon(painter = painterResource(R.drawable.ic_login), contentDescription = null)
        Spacer(modifier = Modifier.size(8.dp))
        Text(text = stringResource(R.string.eclipse_login_microsoft))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = TextOnImageDim.copy(alpha = 0.3f))
        Text(
            modifier = Modifier.padding(horizontal = 8.dp),
            text = stringResource(R.string.eclipse_login_or),
            style = MaterialTheme.typography.labelMedium,
            color = TextOnImageDim
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = TextOnImageDim.copy(alpha = 0.3f))
    }

    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = nick,
        onValueChange = { nick = it.trim().take(16) },
        singleLine = true,
        label = { Text(stringResource(R.string.eclipse_login_nick_hint)) },
        supportingText = { Text(stringResource(R.string.eclipse_login_nick_rule)) },
        isError = nick.isNotEmpty() && !nickValid,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submitNick() })
    )

    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        enabled = nickValid,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextOnImage),
        onClick = { submitNick() }
    ) {
        Text(text = stringResource(R.string.eclipse_login_nick_continue))
    }
}

@Composable
private fun AccountContent(
    account: Account,
    onSwitchAccount: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlayerFace(account = account, avatarSize = 48.dp)
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.username,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    if (account.isMicrosoftAccount()) R.string.eclipse_account_microsoft
                    else R.string.eclipse_account_offline
                ),
                style = MaterialTheme.typography.labelMedium,
                color = TextOnImageDim
            )
        }
        TextButton(onClick = onSwitchAccount) {
            Text(text = stringResource(R.string.eclipse_account_switch))
        }
    }
}
