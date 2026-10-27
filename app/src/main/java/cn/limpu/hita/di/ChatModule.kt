package cn.limpu.hita.di

import cn.limpu.hita.agent.llm.ChatRequestRunner
import cn.limpu.hita.agent.llm.LlmChatRequestRunner
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.repository.chat.ChatSessionStore
import cn.limpu.hita.data.repository.chat.RoomChatSessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChatModule {
    @Provides
    @Singleton
    fun provideChatAttachmentProcessor(application: android.app.Application) =
        cn.limpu.hita.agent.attachment.ChatAttachmentProcessor(
            application.cacheDir,
            cn.limpu.hita.agent.attachment.DocumentTextParser(),
        ) { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }

    @Provides
    fun provideChatAttachmentLoader(loader: cn.limpu.hita.agent.attachment.AndroidChatAttachmentLoader):
        cn.limpu.hita.agent.attachment.ChatAttachmentLoader = loader

    @Provides
    @Singleton
    fun provideChatSessionStore(database: AppDatabase): ChatSessionStore = RoomChatSessionStore(database)

    @Provides
    fun provideChatRequestRunner(runner: LlmChatRequestRunner): ChatRequestRunner = runner
}
