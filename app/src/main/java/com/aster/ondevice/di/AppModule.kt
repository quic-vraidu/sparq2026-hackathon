package com.aster.ondevice.di

import com.aster.ondevice.asr.AndroidAsrEngine
import com.aster.ondevice.asr.AsrEngine
import com.aster.ondevice.llm.LlmEngine
import com.aster.ondevice.llm.RoutingLlmEngine
import com.aster.ondevice.tts.AndroidTtsEngine
import com.aster.ondevice.tts.TtsEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module — binds engine interfaces.
 *
 * LlmEngine is bound to RoutingLlmEngine which delegates to:
 *   Phase 2 (default): GenieEngine  (Qualcomm Genie SDK NPU)
 *   Phase 4:           QaicEngine   (QAIC cloud REST API)
 *
 * To switch at runtime: select backend in Settings → configure path/credentials → Load.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindLlmEngine(impl: RoutingLlmEngine): LlmEngine

    @Binds
    @Singleton
    abstract fun bindAsrEngine(impl: AndroidAsrEngine): AsrEngine

    @Binds
    @Singleton
    abstract fun bindTtsEngine(impl: AndroidTtsEngine): TtsEngine
}
