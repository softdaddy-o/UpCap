package com.upcap.di

import android.content.Context
import com.upcap.pipeline.SubtitleGenerator
import com.upcap.pipeline.UpscaleProcessor
import com.upcap.pipeline.VideoDecoder
import com.upcap.pipeline.VideoExporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideVideoDecoder(@ApplicationContext context: Context): VideoDecoder {
        return VideoDecoder(context)
    }

    @Provides
    @Singleton
    fun provideUpscaleProcessor(@ApplicationContext context: Context): UpscaleProcessor {
        return UpscaleProcessor(context)
    }

    @Provides
    @Singleton
    fun provideSubtitleGenerator(@ApplicationContext context: Context): SubtitleGenerator {
        return SubtitleGenerator(context)
    }

    @Provides
    @Singleton
    fun provideVideoExporter(@ApplicationContext context: Context): VideoExporter {
        return VideoExporter(context)
    }
}
