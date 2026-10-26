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

/**
 * Mod instalado só no celular
 * @param modId id do mod no Fabric, usado para não duplicar se o modpack já trouxer o mesmo mod
 */
data class MobileExtraMod(
    val modId: String,
    val file: EclipseManifest.ManifestFile
)

/**
 * Tudo o que é específico do servidor Eclipse Cobblemon fica aqui.
 * As URLs são as mesmas do launcher de PC (painel Voxelith), então PC e celular
 * sempre usam o mesmo modpack, IP e links.
 */
object EclipseConfig {
    /** Nome da versão instalada em .minecraft/versions (também é a pasta isolada do jogo) */
    const val VERSION_NAME = "Eclipse Cobblemon"

    private const val PANEL_BASE = "https://launchers.voxelith.dev/eclipse-cobblemon/main"

    /** Lista de arquivos do modpack (mods, configs, shaders...) */
    const val MANIFEST_URL = "$PANEL_BASE/manifest.json"

    /** Endereço do servidor e links (Discord, loja, site) */
    const val PANEL_URL = "$PANEL_BASE/launcher.json"

    /** Notícias exibidas na tela inicial */
    const val NEWS_URL = "$PANEL_BASE/news.json"

    /** Valores usados quando o painel não responde e ainda não há cache */
    const val DEFAULT_SERVER_NAME = "Eclipse Cobblemon"
    const val DEFAULT_SERVER_ADDRESS = "eclipsecobblemon.mineserver.uno"
    const val DEFAULT_DISCORD = "https://discord.gg/UCcXN3xqmn"
    const val DEFAULT_STORE = "https://eclipsecobblemon.tech/loja.html"
    const val DEFAULT_WEBSITE = "https://eclipsecobblemon.tech"

    /** Protocolo do Minecraft 1.21.1, usado só para consultar o status do servidor */
    const val STATUS_PROTOCOL = 767

    /**
     * Login Microsoft: o mesmo app da Azure do launcher de PC.
     * O fluxo é "authorization code + PKCE" com retorno em localhost, que a WebView intercepta.
     */
    const val MICROSOFT_REDIRECT_URI = "http://localhost:59016/auth/callback"

    /**
     * Mods do pack que não funcionam no Android e são desativados só no celular.
     * Para cada id, o launcher cria em mods/ um mod vazio com o mesmo id e versão maior;
     * o Fabric escolhe esse no lugar do original (mesmo quando o original vem dentro de outro jar).
     * - axiom: traz a biblioteca nativa do ImGui só para PC (x86-64) e fecha o jogo ao iniciar
     */
    val MOBILE_DISABLED_MODS = listOf("axiom")

    /**
     * Mods que só o celular instala, além do modpack (só do lado do jogador, não afetam o servidor).
     * Se o modpack passar a trazer o mesmo mod, o extra é ignorado para não duplicar.
     * - ModernFix: menos memória e carregamento mais rápido em packs grandes
     */
    val MOBILE_EXTRA_MODS = listOf(
        MobileExtraMod(
            modId = "modernfix",
            file = EclipseManifest.ManifestFile(
                path = "mods/modernfix-fabric-5.25.1+mc1.21.1.jar",
                url = "https://cdn.modrinth.com/data/nmDcB62a/versions/NnNX8LBn/modernfix-fabric-5.25.1%2Bmc1.21.1.jar",
                sha1 = "4ab39e9c736e7204146792c545ec5b29fc0a3872",
                size = 471258
            )
        )
    )

    /**
     * Configurações de vídeo mais leves, aplicadas ao options.txt uma única vez por instalação
     * (o options.txt do pack foi feito para PC). Depois disso o jogador muda à vontade.
     */
    val MOBILE_OPTIONS = linkedMapOf(
        "renderDistance" to "6",
        "simulationDistance" to "5",
        "graphicsMode" to "0",
        "maxFps" to "60",
        "entityShadows" to "false",
        "biomeBlendRadius" to "0",
        "mipmapLevels" to "2",
        "entityDistanceScaling" to "0.75",
        "particles" to "1"
    )

    /** Regra de nick do modo offline, igual ao launcher de PC */
    val NICK_REGEX = Regex("^[A-Za-z0-9_]{3,16}$")

    /** Troca a tela inicial do Zalith pela tela do Eclipse (login + jogar) */
    const val CUSTOM_HOME = true

    /** Mostra os botões de Download e Terracotta (LAN) na barra do topo */
    const val SHOW_ADVANCED_TOP_BAR = false

    /** O Zalith original verifica atualizações no GitHub dele; aqui isso ficaria errado */
    const val CHECK_UPSTREAM_UPDATES = false

    /** Repositório com o código-fonte desta versão (exigência da GPL-3.0) */
    const val SOURCE_URL = "https://github.com/bigaodospoke/eclipse-cobblemon-mobile"
    const val UPSTREAM_URL = "https://github.com/ZalithLauncher/ZalithLauncher2"
}
