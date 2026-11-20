package com.chernika.assistant

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * Real Qwen3 model inference is exclusively owned by one worker thread.
 * Loading/inference/cleanup never run on the Android UI thread.
 */
class OfflineBrain(
    private val context: Context,
    private val modelFile: File
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    fun generate(prompt: String, done: (Result<String>) -> Unit) {
        executor.execute {
            val result = try {
                if (!modelFile.exists() || modelFile.length() < 300_000_000L) {
                    throw IllegalStateException("Qwen3 model is not installed.")
                }
                if (engine == null) {
                    val config = EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.CPU(threadCount = 2),
                        maxNumTokens = 1024,
                        cacheDir = context.cacheDir.absolutePath
                    )
                    val candidate = Engine(config)
                    // Never close an Engine for which initialize() failed.
                    candidate.initialize()
                    engine = candidate
                    conversation = candidate.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(
                                "You are Chernika AI. Be helpful, accurate, " +
                                    "warm and concise. Adapt to the user's language. " +
                                    "You are an offline AI; you cannot browse the web."
                            ),
                            samplerConfig = SamplerConfig(
                                topK = 20, topP = 0.95, temperature = 0.6
                            ),
                            maxOutputToken = 192
                        )
                    )
                }
                val answer = checkNotNull(conversation).sendMessage(prompt).toString()
                Result.success(answer)
            } catch (e: Exception) {
                // Native failures can poison subsequent calls (task state 7).
                val badConversation = conversation
                conversation = null
                try { badConversation?.close() } catch (_: Exception) {}
                val badEngine = engine
                engine = null
                try { badEngine?.close() } catch (_: Exception) {}
                Result.failure<String>(e)
            }
            done(result)
        }
    }

    fun close() {
        executor.execute {
            val c = conversation
            conversation = null
            try { c?.close() } catch (_: Exception) {}
            val e = engine
            engine = null
            try { e?.close() } catch (_: Exception) {}
        }
        executor.shutdown()
    }
}
