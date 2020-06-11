package com.github.mrbean355.admiralbulldog.game

import com.github.mrbean355.admiralbulldog.arch.repo.DiscordBotRepository
import com.github.mrbean355.admiralbulldog.persistence.ConfigPersistence
import com.github.mrbean355.admiralbulldog.triggers.OnHeal
import com.github.mrbean355.admiralbulldog.triggers.SOUND_TRIGGER_TYPES
import com.github.mrbean355.admiralbulldog.triggers.SoundTrigger
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.reflect.full.createInstance

private val logger = LoggerFactory.getLogger("GameStateMonitor")
private val discordBotRepository by lazy { DiscordBotRepository() }
private val soundTriggers = mutableListOf<SoundTrigger>()
private var previousState: GameState? = null

/** Receives game state updates from Dota 2. */
fun monitorGameStateUpdates(onNewGameState: (GameState) -> Unit) {
    thread(isDaemon = true) {
        embeddedServer(Netty, ConfigPersistence.getPort()) {
            install(ContentNegotiation) {
                gson()
            }
            routing {
                post {
                    try {
                        val gameState = call.receive<GameState>()
                        processGameState(gameState)
                        onNewGameState(gameState)
                    } catch (t: Throwable) {
                        logger.error("Exception during game state update", t)
                    }
                    call.respond(OK)
                }
            }
        }.start(wait = true)
    }
}

/** Play sound bites that want to be played. */
private fun processGameState(currentState: GameState) {
    val previousMatchId = previousState?.map?.matchid
    val currentMatchId = currentState.map?.matchid

    // Recreate sound bites when a new match is entered:
    if (currentMatchId != previousMatchId) {
        previousState = null
        soundTriggers.clear()
        soundTriggers.addAll(SOUND_TRIGGER_TYPES.map { it.createInstance() })
    }

    // Play sound bites that want to be played:
    val localPreviousState = previousState
    if (localPreviousState != null && localPreviousState.hasValidProperties() && currentState.hasValidProperties() && currentState.map?.paused == false) {
        soundTriggers
                .filter { ConfigPersistence.isSoundTriggerEnabled(it::class) }
                .filter { it.shouldPlay(localPreviousState, currentState) }
                .filter { it.doesProc(localPreviousState, currentState) }
                .forEach { playSoundForType(it) }
    }
    previousState = currentState
}

private fun playSoundForType(soundTrigger: SoundTrigger) {
    val choices = ConfigPersistence.getSoundsForType(soundTrigger::class)
    if (choices.isNotEmpty()) {
        val choice = choices.random()
        if (shouldPlayOnDiscord(soundTrigger)) {
            GlobalScope.launch {
                val response = discordBotRepository.playSound(choice)
                if (!response.isSuccessful()) {
                    choice.play(rate = soundTrigger.randomRate())
                }
            }
        } else {
            choice.play(rate = soundTrigger.randomRate())
        }
    }
}

private fun shouldPlayOnDiscord(soundTrigger: SoundTrigger): Boolean {
    return ConfigPersistence.isUsingDiscordBot() && ConfigPersistence.isPlayedThroughDiscord(soundTrigger::class)
}

/** @return `true` if the randomised chance falls within the user's chosen chance. */
private fun SoundTrigger.doesProc(previousState: GameState, currentState: GameState): Boolean {
    if (this is OnHeal && ConfigPersistence.isUsingHealSmartChance()) {
        return doesSmartChanceProc(previousState, currentState)
    }
    val chance = ConfigPersistence.getSoundTriggerChance(this::class) / 100.0
    return Random.nextDouble() < chance
}

/** @return a randomised playback rate. */
private fun SoundTrigger.randomRate(): Int {
    val min = ConfigPersistence.getSoundTriggerMinRate(this::class)
    val max = ConfigPersistence.getSoundTriggerMaxRate(this::class)
    return if (min == max) min else Random.nextInt(min, max)
}
