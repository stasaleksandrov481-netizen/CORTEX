package app.cortex.assistant

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal object VoiceDownload {
    private val redirects = setOf("github.com", "raw.githubusercontent.com",
        "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")

    fun catalog(url: String, publicKey: String? = null): String {
        val connection = connect(URI(url), "application/json").apply {
            if (publicKey != null) setRequestProperty("apikey", publicKey)
        }
        try {
            connection.inputStream.use { input ->
                val bytes = input.readNBytesCompat(192_001)
                require(bytes.size <= 192_000)
                return bytes.toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }

    fun fetch(asset: VoiceAsset, target: File, progress: (Long) -> Unit) {
        require(VoiceAsset.isGitHubVoiceUrl(asset.url))
        var url = asset.url
        repeat(5) {
            val connection = connect(url, "application/octet-stream")
            try {
                val response = connection.responseCode
                if (response in 300..399) {
                    val redirect = url.resolve(connection.getHeaderField("Location") ?: throw IOException("Redirect without a location"))
                    require(redirect.scheme == "https" && redirect.host in redirects && redirect.port == -1 &&
                        redirect.userInfo == null)
                    url = redirect
                } else {
                    if (response != HttpURLConnection.HTTP_OK) throw IOException("GitHub responded with $response")
                    val length = connection.contentLengthLong
                    if (length >= 0 && length != asset.bytes) throw IOException("Wrong asset size")
                    val hash = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    connection.inputStream.use { input ->
                        target.outputStream().buffered().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                if (copied > asset.bytes) throw IOException("Asset exceeded its declared size")
                                hash.update(buffer, 0, count)
                                out.write(buffer, 0, count)
                                progress(copied)
                            }
                        }
                    }
                    val received = hash.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
                    if (copied != asset.bytes || received != asset.sha256) throw IOException("Asset checksum mismatch")
                    return
                }
            } finally { connection.disconnect() }
        }
        throw IOException("Too many redirects")
    }

    fun unpack(archive: File, directory: File) {
        directory.mkdirs()
        val buffer = ByteArray(64 * 1024)
        var expanded = 0L
        var entries = 0
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > 1600) throw IOException("Too many files in the voice pack")
                val file = packPath(directory, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    if (file.exists()) throw IOException("Duplicate file in the voice pack")
                    file.parentFile?.mkdirs()
                    file.outputStream().buffered().use { out ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            expanded += count
                            if (expanded > 350_000_000) throw IOException("Voice pack is too large")
                            out.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        require(entries > 0)
    }

    private fun connect(url: URI, accept: String) = (url.toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 12_000
        readTimeout = 25_000
        setRequestProperty("Accept", accept)
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("User-Agent", "CORTEX-Android")
    }

    private fun InputStream.readNBytesCompat(max: Int): ByteArray {
        val bytes = ByteArray(max)
        var size = 0
        while (size < max) {
            val count = read(bytes, size, max - size)
            if (count < 0) break
            size += count
        }
        return bytes.copyOf(size)
    }
}
