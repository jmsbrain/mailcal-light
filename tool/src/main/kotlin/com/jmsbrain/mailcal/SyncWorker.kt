package com.jmsbrain.mailcal

import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlin.time.Duration.Companion.minutes

/** Refreshes the offline copy in the background, so new events arrive without opening the app. */
@LightJob("sync")
val syncJob: LightJobHandler = { ctx, _ ->
    Repo.init(ctx)
    Feeds.init(ctx)
    val s = Repo.state.value
    if (s.account != null && !s.syncing) Repo.sync()
    Feeds.refreshAll()
    LightJobResult.Success(outputFilePath = null, message = null)
}

object SyncWorker {
    private const val NAME = "sync"
    private val EVERY = 30.minutes

    fun start(ctx: SealedLightContext) {
        LightWork.enqueuePeriodic(ctx, NAME, EVERY)
    }

    fun stop(ctx: SealedLightContext) {
        LightWork.cancel(ctx, NAME)
    }
}
