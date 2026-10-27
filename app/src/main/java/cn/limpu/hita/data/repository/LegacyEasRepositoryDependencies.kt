package cn.limpu.hita.data.repository

import cn.limpu.hita.data.source.web.eas.BenbuEASWebSource
import cn.limpu.hita.data.source.web.eas.EASWebSource
import cn.limpu.hita.data.source.web.eas.WeihaiEASWebSource
import cn.limpu.hita.data.source.web.service.EASService
import cn.limpu.hita.data.source.web.service.EASShenzhenService

internal object LegacyEasRepositorySourceFactory {
    fun shenzhen(
        onTokenRefreshed: (cn.limpu.hita.data.model.eas.EASToken) -> Unit,
        credentialProvider: (cn.limpu.hita.data.model.eas.EASToken.Campus, String?) ->
            cn.limpu.hita.data.source.preference.EasCredential?,
    ): EASShenzhenService = EASWebSource(
        onTokenRefreshed = onTokenRefreshed,
        credentialProvider = credentialProvider,
    )

    fun benbu(onTokenRefreshed: (cn.limpu.hita.data.model.eas.EASToken) -> Unit): EASService =
        BenbuEASWebSource(onTokenRefreshed)

    fun weihai(onTokenRefreshed: (cn.limpu.hita.data.model.eas.EASToken) -> Unit): EASService =
        WeihaiEASWebSource(onTokenRefreshed)
}

