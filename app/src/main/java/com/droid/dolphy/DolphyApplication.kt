package com.droid.dolphy

import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import com.droid.dolphy.plugin.PluginManager
import com.droid.dolphy.plugin.PluginDownloadPolicy

private object NoMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float = 0f
}

class DolphyApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    @OptIn(InternalComposeUiApi::class)
    override fun onCreate() {
        super.onCreate()
        disableProcessAnimations()
        WindowRecomposerPolicy.setFactory { rootView ->
            rootView.createLifecycleAwareWindowRecomposer(
                coroutineContext = NoMotionDurationScale,
            )
        }
        PluginDownloadPolicy.initialize(this)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val log = buildString {
                append("Thread: ")
                append(thread.name)
                append('\n')
                append("Time: ")
                append(java.time.Instant.now().toString())
                append('\n')
                append(android.util.Log.getStackTraceString(throwable))
            }
            runCatching { java.io.File(filesDir, "crash_stack.txt").writeText(log) }
            if (PluginManager.hasRunningPlugins()) {
                PluginManager.activateSafeModeFromCrash(this, log)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
        try {
            PluginManager.initialize(this)
        } catch (t: Throwable) {
            android.util.Log.e("DolphyApplication", "Plugin init failed", t)
        }
    }

    /**
     * Compose and the platform property animators read the process animator
     * duration scale. Setting it before the first frame makes every in-app
     * animation finish immediately without touching the user's system-wide
     * developer settings.
     */
    private fun disableProcessAnimations() {
        runCatching {
            val setDurationScale = ValueAnimator::class.java.getDeclaredMethod(
                "setDurationScale",
                Float::class.javaPrimitiveType,
            )
            setDurationScale.isAccessible = true
            setDurationScale.invoke(null, 0f)
            Log.i("DolphyApplication", "App animations disabled; enabled=${ValueAnimator.areAnimatorsEnabled()}")
        }.onFailure { failure ->
            Log.w("DolphyApplication", "Could not disable app animations", failure)
        }
    }
}

