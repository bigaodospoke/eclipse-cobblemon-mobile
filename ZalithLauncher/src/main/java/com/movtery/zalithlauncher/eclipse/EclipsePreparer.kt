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
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.coroutine.TaskFlowExecutor
import com.movtery.zalithlauncher.coroutine.TaskLogOutput
import com.movtery.zalithlauncher.coroutine.TitledTask
import com.movtery.zalithlauncher.coroutine.addTask
import com.movtery.zalithlauncher.coroutine.buildPhase
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.fabric.FabricVersion
import com.movtery.zalithlauncher.game.download.game.GameDownloadInfo
import com.movtery.zalithlauncher.game.download.game.GameInstaller
import com.movtery.zalithlauncher.game.path.getGameHome
import com.movtery.zalithlauncher.game.path.getVersionsHome
import com.movtery.zalithlauncher.game.version.download.DownloadTask
import com.movtery.zalithlauncher.game.version.download.runBatchDownloads
import com.movtery.zalithlauncher.game.version.installed.SettingState
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionConfig
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.game.version.multiplayer.AllServers
import com.movtery.zalithlauncher.game.version.multiplayer.ServerData
import com.movtery.zalithlauncher.path.GLOBAL_JSON
import com.movtery.zalithlauncher.ui.androidText
import com.movtery.zalithlauncher.utils.file.compareSHA1
import com.movtery.zalithlauncher.utils.file.formatFileSize
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.ServerAddress
import com.movtery.zalithlauncher.utils.network.isUsingMobileData
import com.movtery.zalithlauncher.utils.platform.bytesToMB
import com.movtery.zalithlauncher.utils.platform.getMaxMemoryForSettings
import com.movtery.zalithlauncher.utils.platform.getTotalMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.min

private const val TAG = "EclipsePreparer"

/** Prefixo dos mods vazios que desativam mods incompatíveis com o Android */
private const val DISABLED_STUB_PREFIX = "zz-eclipse-celular-desativa-"

/** Acima disso, em dados móveis, o jogador confirma antes de baixar */
private const val MOBILE_DATA_CONFIRM_BYTES = 20L * 1024 * 1024

/**
 * O que já foi instalado na pasta da versão, para não reverificar 450 MB de mods a cada abertura.
 * @param files arquivos gerenciados (os que não são "keep"): caminho -> sha1 instalado
 */
@Serializable
private data class SyncState(
    val minecraft: String? = null,
    val loader: String? = null,
    val modpackVersion: String? = null,
    val files: Map<String, String> = emptyMap(),
    /** Perfil de vídeo do celular já aplicado ao options.txt (só uma vez, depois vale o que o jogador escolher) */
    val mobileOptionsApplied: Boolean = false
)

/** O jogador recusou baixar usando dados móveis */
class MobileDataDeclinedException : CancellationException("Download over mobile data declined")

/**
 * Deixa tudo pronto para entrar no servidor:
 * 1. baixa manifest e painel;
 * 2. instala (ou reinstala) Minecraft + Fabric na versão "Eclipse Cobblemon", com pasta isolada;
 * 3. sincroniza mods/configs com o manifest (só baixa o que mudou e apaga mods removidos do pack);
 * 4. grava o IP do servidor na versão, para o jogo já abrir conectando.
 *
 * @param confirmMobileData chamado quando há muito para baixar em dados móveis; true = continuar
 */
class EclipsePreparer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val confirmMobileData: suspend (bytes: Long?) -> Boolean
) {
    private val taskExecutor = TaskFlowExecutor(scope)
    val tasksFlow: StateFlow<List<TitledTask>> = taskExecutor.tasksFlow

    private val logOutput = MutableStateFlow<TaskLogOutput?>(null)

    private lateinit var manifest: EclipseManifest
    private lateinit var panel: EclipsePanel
    private var readyVersion: Version? = null

    private val versionDir: File
        get() = File(getVersionsHome(getGameHome()), EclipseConfig.VERSION_NAME)

    /**
     * @param onReady versão pronta e endereço do servidor para entrar
     */
    fun start(
        onReady: (version: Version, serverAddress: String) -> Unit,
        onError: (Throwable) -> Unit,
        onCancel: () -> Unit
    ) {
        taskExecutor.executePhasesAsync(
            onStart = {
                taskExecutor.addPhase(checkPhase())
            },
            onComplete = {
                val version = readyVersion
                if (version != null) onReady(version, panel.server.address)
                else onError(IllegalStateException("Version ${EclipseConfig.VERSION_NAME} is not valid"))
            },
            onError = onError,
            onCancel = onCancel
        )
    }

    fun cancel() {
        taskExecutor.cancel()
    }

    private fun checkPhase() = buildPhase {
        addTask(
            id = "Eclipse.Check",
            title = androidText(R.string.eclipse_task_check),
            icon = R.drawable.ic_build_outlined,
            dispatcher = Dispatchers.IO
        ) { task ->
            task.updateProgress(-1f)
            panel = EclipseRemote.fetchPanel()
            manifest = withMobileExtras(EclipseRemote.fetchManifest())
            Logger.info(TAG, "Modpack ${manifest.name} ${manifest.version}: MC ${manifest.minecraft}, Fabric ${manifest.loader.version}, ${manifest.files.size} files")

            val dir = versionDir
            val state = readState(dir)
            val versionJson = File(dir, "${EclipseConfig.VERSION_NAME}.json")
            val needsInstall = !versionJson.exists() ||
                    state.minecraft != manifest.minecraft ||
                    state.loader != manifest.loader.version

            val pending = pendingFiles(dir, state)
            val pendingBytes = pending.sumOf { it.size }

            if (isUsingMobileData(context) && (needsInstall || pendingBytes > MOBILE_DATA_CONFIRM_BYTES)) {
                //Minecraft + bibliotecas não têm tamanho conhecido antes de começar
                val known = if (needsInstall) null else pendingBytes
                if (!confirmMobileData(known)) throw MobileDataDeclinedException()
            }

            if (needsInstall) {
                val installer = GameInstaller(
                    context = context,
                    info = GameDownloadInfo(
                        gameVersion = manifest.minecraft,
                        customVersionName = EclipseConfig.VERSION_NAME,
                        fabric = FabricVersion(
                            inherit = manifest.minecraft,
                            version = manifest.loader.version,
                            stable = true
                        )
                    ),
                    scope = scope,
                    logOutputHolder = logOutput
                )
                //Já instalada com outra versão do Minecraft/Fabric: refaz no lugar, sem tocar em mods e mundos
                val installPhases = if (versionJson.exists()) {
                    installer.getModifyTaskPhase()
                } else {
                    installer.getTaskPhase(createIsolation = true)
                }
                taskExecutor.addPhases(installPhases)
            }

            taskExecutor.addPhase(syncPhase(recordInstall = needsInstall))
        }
    }

    private fun syncPhase(recordInstall: Boolean) = buildPhase {
        addTask(
            id = "Eclipse.Sync",
            title = androidText(R.string.eclipse_task_sync),
            icon = R.drawable.ic_download_2_filled,
            dispatcher = Dispatchers.IO
        ) { task ->
            val dir = versionDir
            var state = readState(dir)
            if (recordInstall) {
                state = state.copy(minecraft = manifest.minecraft, loader = manifest.loader.version)
                writeState(dir, state)
            }

            val pending = pendingFiles(dir, state)
            if (pending.isNotEmpty()) {
                val totalBytes = pending.sumOf { it.size }
                Logger.info(TAG, "Downloading ${pending.size} file(s), ${formatFileSize(totalBytes)}")
                val downloads = pending.map { file ->
                    DownloadTask(
                        urls = listOf(file.url),
                        verifyIntegrity = true,
                        targetFile = resolveSafe(dir, file.path),
                        sha1 = file.sha1,
                        size = file.size
                    )
                }
                task.runBatchDownloads(
                    tasks = downloads,
                    maxConnections = 16,
                    retryRounds = 2,
                    onSnapshot = { snapshot ->
                        task.updateSpeed(snapshot.speedBytesPerSec)
                        task.updateMessage(
                            androidText(
                                R.string.eclipse_task_sync_progress,
                                snapshot.downloadedFiles,
                                snapshot.totalFiles,
                                formatFileSize(snapshot.downloadedBytes),
                                formatFileSize(totalBytes)
                            )
                        )
                    }
                )
            }

            task.updateProgress(-1f)
            task.updateMessage(null)

            //Apaga apenas arquivos que este launcher instalou e que saíram do modpack
            val managed = manifest.files.filterNot { it.keep }
            val managedPaths = managed.mapTo(HashSet()) { it.path }
            state.files.keys
                .filter { it !in managedPaths && isSafeRelativePath(it) }
                .forEach { path ->
                    val file = resolveSafe(dir, path)
                    if (file.isFile && file.delete()) Logger.info(TAG, "Removed file no longer in the modpack: $path")
                }

            if (!state.mobileOptionsApplied) applyMobileOptions(dir)

            writeState(
                dir,
                state.copy(
                    modpackVersion = manifest.version,
                    files = managed.associate { it.path to it.sha1 },
                    mobileOptionsApplied = true
                )
            )

            writeDisabledModStubs(dir)
        }

        addTask(
            id = "Eclipse.Finish",
            title = androidText(R.string.eclipse_task_finish),
            icon = R.drawable.ic_rocket_launch_filled,
            dispatcher = Dispatchers.IO
        ) { task ->
            task.updateProgress(-1f)
            val dir = versionDir
            VersionConfig.parseConfig(dir).apply {
                isolationType = SettingState.ENABLE
                serverIp = panel.server.address
                versionSummary = "${manifest.name} ${manifest.version}"
                //Só define se o jogador ainda não escolheu a memória desta versão
                if (ramAllocation < 256) ramAllocation = recommendedRam()
            }.save()

            ensureServerInList(dir)

            val gameHome = getGameHome()
            readyVersion = VersionsManager.loadVersion(gameHome, EclipseConfig.VERSION_NAME)
                ?.takeIf { it.isValid() }
            //Atualiza a lista de versões do resto do app em segundo plano
            VersionsManager.refresh(TAG, trySetVersion = EclipseConfig.VERSION_NAME)
        }
    }

    /**
     * Junta ao modpack os mods que só o celular usa ([EclipseConfig.MOBILE_EXTRA_MODS]).
     * Pula o extra se o modpack já tiver um jar com o mesmo id no nome, para não carregar o mod duas vezes.
     */
    private fun withMobileExtras(base: EclipseManifest): EclipseManifest {
        val fileNames = base.files.map { it.path.substringAfterLast('/').lowercase() }
        val extras = EclipseConfig.MOBILE_EXTRA_MODS
            .filter { extra -> fileNames.none { it.contains(extra.modId) } }
            .map { it.file }
            .filter { extra -> base.files.none { it.path == extra.path } }
        return if (extras.isEmpty()) base else base.copy(files = base.files + extras)
    }

    /**
     * Troca no options.txt as opções de [EclipseConfig.MOBILE_OPTIONS] (cria as que faltarem)
     */
    private fun applyMobileOptions(dir: File) {
        val file = File(dir, "options.txt")
        val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
        EclipseConfig.MOBILE_OPTIONS.forEach { (key, value) ->
            val line = "$key:$value"
            val index = lines.indexOfFirst { it.substringBefore(':') == key }
            if (index >= 0) lines[index] = line else lines.add(line)
        }
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        Logger.info(TAG, "Applied the mobile video profile to options.txt")
    }

    /**
     * Coloca o servidor na lista de Multijogador (servers.dat) com o pacote de recursos ativado:
     * o jogador consegue reconectar pela lista e o jogo aceita o pacote do servidor sem perguntar.
     * Se o servidor já estiver na lista, respeita o que o jogador escolheu.
     */
    private suspend fun ensureServerInList(dir: File) {
        val address = panel.server.address
        //O jogo procura na lista exatamente o texto do --quickPlayMultiplayer, que o launcher manda como "host:porta"
        val quickPlayIp = runCatching {
            val parsed = ServerAddress.parse(address)
            "${parsed.getASCIIHost()}:${parsed.port.takeIf { it >= 0 } ?: ServerAddress.DEFAULT_PORT}"
        }.getOrDefault(address)

        val servers = AllServers()
        servers.loadServers(File(dir, "servers.dat"))
        if (servers.serverList.any { it.originIp.equals(quickPlayIp, ignoreCase = true) }) return

        //Versões anteriores do launcher gravaram o endereço sem a porta: corrige essa entrada
        val oldEntry = servers.serverList.firstOrNull { it.originIp.equals(address, ignoreCase = true) }
        if (oldEntry != null) {
            oldEntry.originIp = quickPlayIp
            oldEntry.texturePackStatus = ServerData.TexturePackStatus.ENABLED
        } else {
            servers.addServer(
                ServerData(
                    name = panel.server.name,
                    originIp = quickPlayIp,
                    texturePackStatus = ServerData.TexturePackStatus.ENABLED
                )
            )
        }
        servers.save(dir)
        Logger.info(TAG, "Server $quickPlayIp is in the multiplayer list with the resource pack enabled")
    }

    /**
     * Cria um mod vazio para cada id de [EclipseConfig.MOBILE_DISABLED_MODS] e apaga os que saíram da lista.
     * Com versão maior e estando direto em mods/, ele vence o mod original no Fabric.
     */
    private fun writeDisabledModStubs(dir: File) {
        val modsDir = File(dir, "mods").apply { mkdirs() }
        val wanted = EclipseConfig.MOBILE_DISABLED_MODS.associateBy { "$DISABLED_STUB_PREFIX$it.jar" }

        modsDir.listFiles()
            ?.filter { it.name.startsWith(DISABLED_STUB_PREFIX) && it.name !in wanted }
            ?.forEach { it.delete() }

        wanted.forEach { (fileName, modId) ->
            val json = """{"schemaVersion":1,"id":"$modId","version":"999.0.0+eclipse-mobile",""" +
                    """"name":"$modId (desativado no celular)","description":"Este mod não funciona no Android.","environment":"*"}"""
            val stub = File(modsDir, fileName)
            val current = runCatching {
                ZipFile(stub).use { zip -> zip.getInputStream(zip.getEntry("fabric.mod.json")).reader().readText() }
            }.getOrNull()
            if (current == json) return@forEach

            ZipOutputStream(stub.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("fabric.mod.json"))
                zip.write(json.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            Logger.info(TAG, "Disabled mod on mobile: $modId")
        }
    }

    /**
     * Memória para o modpack (o padrão global do launcher para em 2 GB, pouco para o Cobblemon).
     * Os limites usam a memória que o Android informa, que fica abaixo da anunciada (8 GB ≈ 7,4 GB).
     */
    private fun recommendedRam(): Int {
        val deviceRam = getTotalMemory(context).bytesToMB()
        val target = when {
            deviceRam >= 11000 -> 4096
            deviceRam >= 7000 -> 3584
            deviceRam >= 5200 -> 3072
            else -> 2560
        }
        return min(target, getMaxMemoryForSettings(context))
    }

    /**
     * Arquivos do manifest que precisam ser baixados:
     * - não existem;
     * - não são "keep" e mudaram (tamanho diferente, ou sha1 diferente do que foi instalado).
     * Só calcula o sha1 de arquivos que este launcher ainda não registrou.
     */
    private fun pendingFiles(dir: File, state: SyncState): List<EclipseManifest.ManifestFile> {
        return manifest.files.filter { file ->
            val target = resolveSafe(dir, file.path)
            when {
                !target.isFile -> true
                file.keep -> false
                target.length() != file.size -> true
                state.files[file.path] == file.sha1 -> false
                else -> !compareSHA1(target, file.sha1)
            }
        }
    }

    /**
     * Resolve um caminho do manifest dentro da pasta da versão, recusando qualquer coisa que saia dela
     */
    private fun resolveSafe(dir: File, path: String): File {
        require(isSafeRelativePath(path)) { "unsafe path $path" }
        val root = dir.canonicalFile
        val target = File(root, path).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "$path escapes the version folder" }
        return target
    }

    private fun stateFile(dir: File) = File(dir, ".eclipse-sync.json")

    private fun readState(dir: File): SyncState = runCatching {
        GLOBAL_JSON.decodeFromString(SyncState.serializer(), stateFile(dir).readText())
    }.getOrDefault(SyncState())

    private fun writeState(dir: File, state: SyncState) {
        dir.mkdirs()
        val file = stateFile(dir)
        val temp = File(dir, ".eclipse-sync.json.tmp")
        temp.writeText(GLOBAL_JSON.encodeToString(SyncState.serializer(), state))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
