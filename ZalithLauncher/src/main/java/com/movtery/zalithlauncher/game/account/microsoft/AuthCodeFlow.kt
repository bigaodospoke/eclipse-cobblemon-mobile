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

package com.movtery.zalithlauncher.game.account.microsoft

import android.net.Uri
import android.util.Base64
import com.movtery.zalithlauncher.BuildKeys
import com.movtery.zalithlauncher.eclipse.EclipseConfig
import kotlinx.coroutines.CompletableDeferred
import java.security.MessageDigest
import java.security.SecureRandom

/** Escopos pedidos pelo launcher de PC; o mesmo app da Azure, o mesmo consentimento */
const val AUTH_CODE_SCOPES = "XboxLive.signin offline_access"

/**
 * Login Microsoft por "authorization code + PKCE" (o app da Azure do servidor não aceita device code).
 *
 * A página de login abre na WebView do launcher; quando a Microsoft redireciona para
 * [EclipseConfig.MICROSOFT_REDIRECT_URI], a WebView entrega a URL para [handleRedirect]
 * em vez de tentar carregar o localhost.
 */
object AuthCodeFlow {
    class Session(
        val authorizeUrl: String,
        val state: String,
        val codeVerifier: String,
        internal val redirect: CompletableDeferred<String>
    ) {
        /**
         * Espera o retorno da página de login e devolve o código de autorização
         * @throws IllegalStateException se a Microsoft devolveu erro ou o state não confere
         */
        suspend fun awaitCode(): String {
            val uri = Uri.parse(redirect.await())
            uri.getQueryParameter("error")?.let { error ->
                val description = uri.getQueryParameter("error_description")
                throw IllegalStateException(description?.let { "$error: $it" } ?: error)
            }
            check(uri.getQueryParameter("state") == state) { "OAuth state mismatch" }
            return uri.getQueryParameter("code") ?: throw IllegalStateException("Missing authorization code")
        }
    }

    @Volatile
    private var pending: CompletableDeferred<String>? = null

    fun newSession(): Session {
        val verifier = randomUrlSafe(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomUrlSafe(24)

        val url = Uri.parse("$MICROSOFT_AUTH_URL/consumers/oauth2/v2.0/authorize").buildUpon()
            .appendQueryParameter("client_id", BuildKeys.OAUTH_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", EclipseConfig.MICROSOFT_REDIRECT_URI)
            .appendQueryParameter("scope", AUTH_CODE_SCOPES)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("prompt", "select_account")
            .build()
            .toString()

        val redirect = CompletableDeferred<String>()
        pending?.cancel()
        pending = redirect
        return Session(url, state, verifier, redirect)
    }

    /**
     * Chamado pela WebView a cada navegação.
     * @return true se era o retorno do login (a WebView não deve carregar essa URL)
     */
    fun handleRedirect(url: String?): Boolean {
        if (url == null || !url.startsWith(EclipseConfig.MICROSOFT_REDIRECT_URI)) return false
        pending?.complete(url)
        pending = null
        return true
    }

    fun cancel(session: Session) {
        session.redirect.cancel()
        if (pending === session.redirect) pending = null
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        return base64Url(buffer)
    }

    private fun base64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
