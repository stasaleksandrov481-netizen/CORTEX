package app.cortex.assistant

import android.content.Context
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

internal class VoiceStore private constructor(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val prefs = context.getSharedPreferences("cortex-voices", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "voices").apply { mkdirs() }
    private val gitCache = AtomicFile(File(root, "catalog-github.json"))
    private val adminCache = AtomicFile(File(root, "catalog-admin.json"))
    private val bundled = context.assets.open("voice/catalog.json").bufferedReader().use {
        JSONArray(it.readText()).voiceEntries(bundled = true)
    }
    @Volatile private var entries = merge(readCache(gitCache) + readCache(adminCache))
    private val listeners = mutableSetOf<(JSONObject) -> Unit>()
    private var refreshing = false
    private var lastRefresh = 0L
    private var catalogError = false
    private var downloading: String? = null
    private var downloadStage = ""
    private var removing: String? = null
    private var progress = 0
    private var notice: String? = null

    val enabled get() = prefs.getBoolean("enabled", true)
    val readText get() = prefs.getBoolean("read-text", false)
    val speed get() = prefs.getFloat("speed", 1f)
    private val selected get() = prefs.getString("selected", "android") ?: "android"

    fun configure(enabled: Boolean, readText: Boolean, speed: Float) {
        prefs.edit().putBoolean("enabled", enabled).putBoolean("read-text", readText)
            .putFloat("speed", speed.coerceIn(.6f, 1.5f)).apply()
    }

    fun selectedPack(): VoicePack? {
        if (selected == "android") return null
        return runCatching {
            val installed = installed(selected) ?: return null
            VoicePack.read(File(File(root, installed.id), installed.revision))
        }.getOrNull()
    }

    fun watch(listener: (JSONObject) -> Unit) {
        listeners += listener
        listener(snapshot())
    }

    fun unwatch(listener: (JSONObject) -> Unit) {
        listeners -= listener
    }

    fun refresh(force: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (refreshing || !force && lastRefresh > 0 && now - lastRefresh < 45_000) return
        lastRefresh = now
        refreshing = true
        catalogError = false
        notifyListeners()
        worker.execute {
            val git = refreshCatalog(GIT_CATALOG, gitCache)
            val admin = refreshCatalog(ADMIN_CATALOG, adminCache, BuildConfig.VOICE_CATALOG_KEY)
            entries = merge(git.getOrElse { readCache(gitCache) } + admin.getOrElse { readCache(adminCache) })
            main.post {
                catalogError = git.isFailure && admin.isFailure
                refreshing = false
                notifyListeners()
            }
        }
    }

    private fun refreshCatalog(url: String, cache: AtomicFile, publicKey: String? = null): Result<List<VoiceEntry>> =
        runCatching {
            val raw = VoiceDownload.catalog(url, publicKey)
            val catalog = JSONArray(raw).voiceEntries()
            runCatching { write(cache, raw) }
            catalog
        }

    private fun readCache(cache: AtomicFile): List<VoiceEntry> =
        runCatching { JSONArray(cache.readFully().decodeToString()).voiceEntries() }.getOrDefault(emptyList())

    fun install(id: String) {
        if (downloading != null || removing != null) return
        val entry = entries.find { it.id == id } ?: return
        if (!Process.is64Bit()) {
            notice = "Скачиваемые голоса требуют 64-битного Android. Системный голос доступен."
            notifyListeners()
            return
        }
        if (installed(id)?.revision == entry.revision) { select(id); return }
        downloading = id
        progress = 0
        downloadStage = "Скачиваю"
        notice = null
        notifyListeners()
        worker.execute {
            val parent = File(root, id).apply { mkdirs() }
            val staging = File(parent, ".pending")
            val version = File(parent, entry.revision)
            val previous = installed(id)
            val success = runCatching {
                staging.deleteRecursively()
                require(staging.mkdirs())
                val pendingBytes = entry.assets.filterNot { it === RuVoicePreset.russianModel && cachedRuVoiceModel(it).isDirectory }
                    .sumOf { it.bytes }
                if (parent.usableSpace in 1 until pendingBytes * 3 + 12_000_000L) throw IllegalStateException("storage")
                var received = 0L
                var percent = 0
                fun report(bytes: Long) {
                    val next = ((bytes * 100) / entry.size).toInt().coerceAtMost(100)
                    if (next == percent) return
                    percent = next
                    main.post { progress = next; notifyListeners() }
                }
                var preset: JSONObject? = null
                for ((index, asset) in entry.assets.withIndex()) {
                    if (asset === RuVoicePreset.russianModel) {
                        val model = sharedRuVoiceModel(asset) { report(received + it) }
                        model.listFiles().orEmpty().filter(File::isFile).forEach { file ->
                            val target = File(staging, file.name)
                            runCatching { android.system.Os.link(file.path, target.path) }
                                .getOrElse { file.copyTo(target) }
                        }
                    } else {
                        val archive = File(staging, ".download-$index")
                        VoiceDownload.fetch(asset, archive) { report(received + it) }
                        if (entry.kind == "ruvoice-preset") preset = RuVoicePreset.parse(archive)
                        else VoiceDownload.unpack(archive, staging)
                        archive.delete()
                    }
                    received += asset.bytes
                    report(received)
                }
                if (entry.kind == "ruvoice-preset" || entry.kind == "ruvoice-voice") {
                    val config = preset ?: RuVoicePreset.defaults(checkNotNull(entry.speaker))
                    File(staging, "voice.json").writeText(config.toString())
                }
                main.post { downloadStage = "Проверяю звук"; notifyListeners() }
                val pack = VoicePack.read(staging)
                require(entry.kind != "zip" || pack.engine == "vits")
                LocalVoice.validate(context, pack)
                if (version.exists()) version.deleteRecursively()
                check(staging.renameTo(version))
                write(AtomicFile(File(parent, "installed.json")), entry.toJson().put("language", pack.language).toString())
            }
            staging.deleteRecursively()
            if (success.isFailure && previous?.revision != entry.revision) version.deleteRecursively()
            main.post {
                downloading = null
                if (success.isSuccess) {
                    select(id, "Голос установлен и выбран")
                    worker.execute {
                        parent.listFiles().orEmpty().filter { it.isDirectory && it != version }.forEach { it.deleteRecursively() }
                    }
                } else {
                    notice = if (success.exceptionOrNull()?.message == "storage") "Недостаточно места для установки голоса."
                        else when (success.exceptionOrNull()) {
                            is UnsupportedRuVoiceSpeaker -> "В этом экспорте выбран голос из дополнительного пака RuVoice. Для импорта выберите штатный голос Silero."
                            else -> "Установка не удалась. Проверьте подключение и файл голоса, затем повторите."
                        }
                    notifyListeners()
                }
            }
        }
    }

    private fun sharedRuVoiceModel(asset: VoiceAsset, progress: (Long) -> Unit): File {
        val directory = cachedRuVoiceModel(asset)
        if (File(directory, "pack.json").isFile) return directory
        val staging = File(root, ".silero-pending")
        staging.deleteRecursively()
        require(staging.mkdirs())
        val archive = File(staging, ".download.zip")
        try {
            VoiceDownload.fetch(asset, archive, progress)
            VoiceDownload.unpack(archive, staging)
            archive.delete()
            check(staging.renameTo(directory))
            return directory
        } finally { staging.deleteRecursively() }
    }

    private fun cachedRuVoiceModel(asset: VoiceAsset) = File(root, ".silero-${asset.sha256.take(16)}")

    fun select(id: String, message: String? = null) {
        if (id == removing || id != "android" && installed(id) == null) return
        if (selected != id || message != null) CortexVoiceOutput.silence()
        prefs.edit().putString("selected", id).apply()
        notice = message
        notifyListeners()
    }

    fun remove(id: String) {
        if (id == "android" || downloading != null || removing != null || installed(id) == null) return
        removing = id
        if (selected == id) select("android")
        notice = null
        notifyListeners()
        worker.execute {
            val parent = File(root, id)
            AtomicFile(File(parent, "installed.json")).delete()
            val removed = parent.deleteRecursively()
            val hasSilero = root.listFiles().orEmpty().any { candidate ->
                val saved = installed(candidate.name)
                saved != null && File(File(candidate, saved.revision), "backbone.pte").isFile
            }
            if (!hasSilero) root.listFiles().orEmpty().filter { it.name.startsWith(".silero-") }.forEach { it.deleteRecursively() }
            main.post {
                removing = null
                notice = if (removed) "Голос удалён" else "Не удалось удалить все файлы голоса."
                notifyListeners()
            }
        }
    }

    private data class Installed(val id: String, val revision: String, val display: JSONObject)
    private fun installed(id: String): Installed? = runCatching {
        if (!Regex("[a-z0-9][a-z0-9-]{1,47}").matches(id)) return null
        val parent = File(root, id)
        val display = JSONObject(AtomicFile(File(parent, "installed.json")).readFully().decodeToString())
        val revision = display.getString("revision")
        if (!Regex("[0-9]{1,7}-[a-f0-9]{16}-[a-f0-9]{16}").matches(revision) ||
            !File(parent, "$revision/voice.json").isFile) return null
        Installed(id, revision, display)
    }.getOrNull()

    private fun merge(remote: List<VoiceEntry>) = (remote + bundled).associateBy { it.id }.values.toList()

    private fun snapshot(): JSONObject {
        val installed = root.listFiles().orEmpty().asSequence().filter { it.isDirectory }
            .mapNotNull { installed(it.name) }.associateBy { it.id }
        val items = JSONArray()
        for (entry in entries) {
            val updated = installed[entry.id]?.revision?.let { it != entry.revision } ?: false
            val bytes = entry.assets.filterNot { it === RuVoicePreset.russianModel && cachedRuVoiceModel(it).isDirectory }.sumOf { it.bytes }
            items.put(entry.toJson().put("size", bytes).put("installed", installed.containsKey(entry.id)).put("update", updated)
                .put("language", installed[entry.id]?.display?.optString("language").orEmpty()))
        }
        for (local in installed.values) if (entries.none { it.id == local.id }) items.put(local.display.put("installed", true))
        return JSONObject().put("type", "voice-catalog").put("items", items)
            .put("selected", selected.takeIf { it in installed } ?: "android").put("loading", refreshing)
            .put("offline", catalogError).put("downloading", downloading.orEmpty()).put("progress", progress)
            .put("stage", downloadStage).put("supported", Process.is64Bit())
            .put("removing", removing.orEmpty()).put("notice", notice.orEmpty())
    }

    private fun notifyListeners() {
        if (listeners.isEmpty()) return
        val status = snapshot()
        listeners.toList().forEach { it(status) }
    }

    private fun write(file: AtomicFile, value: String) {
        val output = file.startWrite()
        try { output.write(value.encodeToByteArray()); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    companion object {
        @Volatile private var instance: VoiceStore? = null
        fun get(context: Context): VoiceStore = instance ?: synchronized(this) {
            instance ?: VoiceStore(context.applicationContext).also { instance = it }
        }

        private const val GIT_CATALOG = "https://raw.githubusercontent.com/stasaleksandrov481-netizen/CORTEX/main/voices/catalog.json"
        private const val ADMIN_CATALOG = "https://jjcqrjztxohnifmziksy.supabase.co/rest/v1/cortex_voice_packs" +
            "?select=id,title,description,version,kind,url,size_bytes,sha256&published=eq.true&order=title&limit=100"
    }
}
