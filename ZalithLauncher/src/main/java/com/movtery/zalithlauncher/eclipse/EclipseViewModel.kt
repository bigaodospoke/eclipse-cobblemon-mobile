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

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.multiplayer.lookupRedirect
import com.movtery.zalithlauncher.game.version.multiplayer.pingServer
import com.movtery.zalithlauncher.game.version.multiplayer.resolve
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.ServerAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "EclipseViewModel"

sealed interface EclipseServerStatus {
    data object Checking : EclipseServerStatus
    data class Online(val players: Int, val maxPlayers: Int, val pingMs: Long) : EclipseServerStatus
    data object Offline : EclipseServerStatus
}

/**
 * Pergunta pendente "baixar usando dados móveis?"
 * @param bytes tamanho conhecido, ou null na primeira instalação
 */
class MobileDataPrompt(
    val bytes: Long?,
    val answer: CompletableDeferred<Boolean> = CompletableDeferred()
)

class EclipseViewModel : ViewModel() {
    private val _panel = MutableStateFlow(EclipseRemote.cachedPanel())
    val panel: StateFlow<EclipsePanel> = _panel.asStateFlow()

    private val _news = MutableStateFlow<List<EclipseNews>>(emptyList())
    val news: StateFlow<List<EclipseNews>> = _news.asStateFlow()

    private val _status = MutableStateFlow<EclipseServerStatus>(EclipseServerStatus.Checking)
    val status: StateFlow<EclipseServerStatus> = _status.asStateFlow()

    private val _preparer = MutableStateFlow<EclipsePreparer?>(null)
    /** Não nulo enquanto o jogo está sendo instalado/atualizado */
    val preparer: StateFlow<EclipsePreparer?> = _preparer.asStateFlow()

    private val _mobileDataPrompt = MutableStateFlow<MobileDataPrompt?>(null)
    val mobileDataPrompt: StateFlow<MobileDataPrompt?> = _mobileDataPrompt.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _panel.value = EclipseRemote.fetchPanel()
            refreshStatus()
        }
        viewModelScope.launch {
            _news.value = EclipseRemote.fetchNews()
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _status.value = EclipseServerStatus.Checking
            _status.value = pingEclipse(_panel.value.server.address)
        }
    }

    /**
     * Instala/atualiza o necessário e devolve a versão pronta para entrar no servidor
     */
    fun play(
        context: Context,
        onReady: (version: Version, serverAddress: String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (_preparer.value != null) return

        val preparer = EclipsePreparer(
            context = context.applicationContext,
            scope = viewModelScope,
            confirmMobileData = { bytes ->
                val prompt = MobileDataPrompt(bytes)
                _mobileDataPrompt.value = prompt
                try {
                    prompt.answer.await()
                } finally {
                    _mobileDataPrompt.value = null
                }
            }
        )
        _preparer.value = preparer
        preparer.start(
            onReady = { version, address ->
                _preparer.value = null
                onReady(version, address)
            },
            onError = { th ->
                _preparer.value = null
                onError(th)
            },
            onCancel = {
                _preparer.value = null
            }
        )
    }

    fun cancel() {
        _mobileDataPrompt.value?.answer?.complete(false)
        _preparer.value?.cancel()
        _preparer.value = null
    }

    private suspend fun pingEclipse(address: String): EclipseServerStatus {
        return try {
            val parsed = ServerAddress.parse(address)
            val target = lookupRedirect(parsed) ?: parsed
            val result = pingServer(
                resolved = target.resolve(),
                protocolVersion = EclipseConfig.STATUS_PROTOCOL,
                timeoutMillis = 8000
            )
            EclipseServerStatus.Online(
                players = result.status.players.online,
                maxPlayers = result.status.players.max,
                pingMs = result.pingMs
            )
        } catch (e: TimeoutCancellationException) {
            Logger.warning(TAG, "Ping to $address timed out", e)
            EclipseServerStatus.Offline
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.warning(TAG, "Could not ping $address", e)
            EclipseServerStatus.Offline
        }
    }
}
