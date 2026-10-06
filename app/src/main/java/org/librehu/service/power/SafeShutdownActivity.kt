package org.librehu.service.power

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.librehu.service.R

/**
 * Screen of the safe shutdown: shown once the audio is muted, the media paused and the storage written (see
 * LibreHuService.safeShutdown). The SoC keeps running: the user can now cut the ignition or unplug the unit.
 * "Resume" brings the sound back.
 */
class SafeShutdownActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val ready = intent.getBooleanExtra(EXTRA_READY, false)
        val title =
            TextView(this).apply {
                text = getString(if (ready) R.string.safe_shutdown_ready else R.string.safe_shutdown_preparing)
                setTextColor(Color.WHITE)
                textSize = 34f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
        val hint =
            TextView(this).apply {
                text = getString(R.string.safe_shutdown_hint)
                setTextColor(0xFF9AA0A6.toInt())
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 48)
            }
        val resume =
            Button(this).apply {
                text = getString(R.string.safe_shutdown_resume)
                textSize = 18f
                setOnClickListener {
                    startService(Intent(this@SafeShutdownActivity, org.librehu.service.LibreHuService::class.java).setAction(ACTION_RESUME))
                    finish()
                }
            }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.BLACK)
                setPadding(48, 48, 48, 48)
                addView(title)
                addView(hint)
                addView(resume)
            },
        )
    }

    @Deprecated("Back does nothing: Resume brings the unit back.")
    override fun onBackPressed() = Unit

    companion object {
        const val EXTRA_READY = "ready"
        const val ACTION_RESUME = "org.librehu.service.SAFE_SHUTDOWN_RESUME"

        fun show(
            context: Context,
            ready: Boolean,
        ) {
            context.startActivity(
                Intent(context, SafeShutdownActivity::class.java)
                    .putExtra(EXTRA_READY, ready)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }
}
