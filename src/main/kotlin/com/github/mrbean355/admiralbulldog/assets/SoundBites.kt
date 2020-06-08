package com.github.mrbean355.admiralbulldog.assets

import com.github.mrbean355.admiralbulldog.arch.repo.DiscordBotRepository
import com.github.mrbean355.admiralbulldog.arch.verifyChecksum
import com.github.mrbean355.admiralbulldog.common.getString
import com.github.mrbean355.admiralbulldog.common.warning
import com.github.mrbean355.admiralbulldog.persistence.ConfigPersistence
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Directory that the downloaded sounds live in. */
private const val SOUNDS_PATH = "sounds"

/**
 * Synchronises our local sounds with the PlaySounds page.
 */
object SoundBites {
    private val logger = LoggerFactory.getLogger(SoundBites::class.java)
    private val playSoundsRepository = DiscordBotRepository()
    private var allSounds = emptyList<SoundBite>()

    /**
     * Synchronise our local sounds with the PlaySounds page.
     * Downloads sounds which don't exist locally.
     * Deletes local sounds which don't exist remotely.
     */
    fun synchronise(action: (String) -> Unit, progress: (Double) -> Unit, complete: (Boolean) -> Unit) {
        val downloaded = AtomicInteger()
        val failed = AtomicInteger()
        val deleted = AtomicInteger()

        GlobalScope.launch(context = IO) {
            val response = playSoundsRepository.listSoundBites()
            val remoteFiles = response.body
            if (!response.isSuccessful() || remoteFiles == null) {
                action(getString("sync_sound_bites_failed"))
                withContext(Main) { complete(false) }
                return@launch
            }
            val invalidLocalFiles = getLocalFiles().filter { it !in remoteFiles.keys }
            val total = remoteFiles.size.toDouble()
            var current = 0

            /* Download all remote files that don't exist locally. */
            coroutineScope {
                remoteFiles.keys.forEach { soundBite ->
                    launch {
                        val existsLocally = soundBiteExistsLocally(soundBite)
                        val latestChecksum = remoteFiles.getValue(soundBite)
                        if (!existsLocally || !File("$SOUNDS_PATH/$soundBite").verifyChecksum(latestChecksum)) {
                            val soundBiteResponse = playSoundsRepository.downloadSoundBite(soundBite, SOUNDS_PATH)
                            if (soundBiteResponse.isSuccessful()) {
                                downloaded.incrementAndGet()
                                action(if (existsLocally) "Re-downloaded: $soundBite" else "Downloaded: $soundBite")
                            } else {
                                failed.incrementAndGet()
                                action("Failed to download: $soundBite")
                                logger.error("Failed to download: $soundBite; statusCode=${soundBiteResponse.statusCode}")
                            }
                        }
                        withContext(Main) {
                            progress(++current / total)
                        }
                    }
                }
            }
            /* Delete local files that don't exist remotely. */
            coroutineScope {
                launch {
                    invalidLocalFiles.forEach {
                        File("${SOUNDS_PATH}/$it").delete()
                        deleted.incrementAndGet()
                        action("Deleted old sound: $it")
                    }
                }
            }

            allSounds = emptyList()
            var message = "Done!\n" +
                    "-> Downloaded ${downloaded.get()} new sound(s).\n" +
                    "-> Deleted ${deleted.get()} old sound(s).\n"
            if (failed.get() > 0) {
                message += "\nFailed to download ${failed.get()} sounds.\n" +
                        "Please restart the app to try again."
            }
            action(message)
            withContext(Main) { complete(failed.get() == 0) }
        }
    }

    /** @return a list of all currently downloaded sounds. */
    fun getAll(): List<SoundBite> {
        if (allSounds.isEmpty()) {
            val root = File(SOUNDS_PATH)
            if (!root.exists() || !root.isDirectory) {
                logger.error("Couldn't find sounds directory")
                return emptyList()
            }
            allSounds = root.list()?.map { SoundBite("$SOUNDS_PATH/$it") }
                    .orEmpty()
        }
        return allSounds
    }

    /**
     * Find a downloaded sound by name.
     * @return the sound if found, `null` otherwise.
     */
    fun findSound(name: String): SoundBite? {
        return getAll().firstOrNull { it.name == name }
    }

    /** Show a warning message if the user selected sounds that don't exist locally. */
    fun checkForInvalidSounds() {
        ConfigPersistence.takeInvalidSounds().also {
            if (it.isNotEmpty()) {
                warning(getString("header_sounds_removed"), getString("msg_sounds_removed", it.joinToString()))
            }
        }
    }

    private fun soundBiteExistsLocally(name: String): Boolean {
        return File("$SOUNDS_PATH/$name").exists()
    }

    private fun getLocalFiles(): List<String> {
        val root = File(SOUNDS_PATH)
        if (!root.exists()) {
            root.mkdirs()
            return emptyList()
        }
        return root.list()?.toList().orEmpty()
    }
}