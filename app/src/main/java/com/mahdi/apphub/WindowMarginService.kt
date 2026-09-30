package com.mahdi.apphub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * The watcher behind the app window margins.
 *
 * A profile is only worth anything if it applies to the app however that app
 * was started - from this hub, or from the vehicle's own launcher, which this
 * app never sees the intent of. So the profiles are not attached to launches
 * at all: this service keeps an eye on which app is in front, and the moment a
 * configured one appears it moves that app's task into its rectangle once.
 *
 * A foreground service, because that is the only thing the platform lets a
 * normal app keep alive for hours. It exists only while the feature is on:
 * with no enabled profile (or with the master switch off) it stops itself, so
 * nothing of this runs for a user who never asked for it - and no notification
 * is shown either.
 *
 * Every look at the system is a root command, hence the pacing: a change of app
 * is followed up quickly, a quiet screen is polled about once a second, and the
 * app that is already in front has its rectangle read back only now and then.
 */
class WindowMarginService : Service() {

    private val profiles by lazy { WindowProfiles(this) }

    @Volatile
    private var watching = true

    private var poller: Thread? = null

    /** the app whose profile was last applied, so a poll does not redo it */
    private var active: ForegroundApp? = null

    /** polls since the last apply, for the periodic read-back */
    private var sinceApply = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!profiles.enabled || profiles.watched().isEmpty()) {
            // nothing to watch: no notification, no thread, no service
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        startWatching()
        return START_STICKY
    }

    override fun onDestroy() {
        watching = false
        poller?.interrupt()
        poller = null
        super.onDestroy()
    }

    // -------------------------------------------------------------- the watch

    private fun startWatching() {
        if (poller?.isAlive == true) return
        poller = Thread({ poll() }, "window-margins").apply {
            isDaemon = true
            start()
        }
    }

    private fun poll() {
        while (watching) {
            val changed = try {
                tick()
            } catch (t: Throwable) {
                // an answer this code did not expect must not end the watch:
                // the next poll simply starts over
                false
            }
            try {
                Thread.sleep(if (changed) BURST_MILLIS else POLL_MILLIS)
            } catch (t: InterruptedException) {
                return
            }
        }
    }

    /**
     * One look at whatever is in front.
     *
     * @return true when the front app changed, which is the moment worth
     *   following up quickly.
     */
    private fun tick(): Boolean {
        if (!profiles.enabled || profiles.watched().isEmpty()) {
            stopSelf()
            return false
        }

        val front = WindowControl.foreground() ?: return false

        if (front == active) {
            sinceApply++
            // The rectangle is read back now and then rather than trusted for
            // ever: a configuration change, or another pass of the window
            // manager, can put a task back where it started.
            if (sinceApply >= DRIFT_POLLS) {
                sinceApply = 0
                reapplyIfMoved(front)
            }
            return false
        }

        active = front
        sinceApply = 0

        // An app with no enabled profile - or one of the apps whose window is
        // never touched - is left exactly as it is. Nothing is reset either:
        // the rectangle of a profile is that app's own, not a mode the unit is
        // put into.
        val profile = profiles.profile(front.packageName)
        if (!profile.enabled || profiles.isProtected(front.packageName)) {
            WindowControl.lastOutcome = null
            return true
        }

        apply(front, profile)
        return true
    }

    /** Moves the app in front into its rectangle, and reports what happened. */
    private fun apply(front: ForegroundApp, profile: WindowProfile) {
        val wanted = bounds(profile)

        // The task is read first: its bounds are what the result is compared
        // against, and a resize nobody can verify is not worth issuing.
        val before = WindowControl.tasks()[front.taskId] ?: return
        if (!WindowControl.resize(front.taskId, wanted)) return

        var after = WindowControl.tasks()[front.taskId] ?: return

        // A resizable task can take a moment to relayout; a fullscreen one never
        // will, and the unit says so immediately - so only the former is retried.
        val resizable = after.windowingMode ?: before.windowingMode
        if (after.bounds == before.bounds && resizable != null && resizable != FULLSCREEN) {
            Thread.sleep(RETRY_MILLIS)
            WindowControl.resize(front.taskId, wanted)
            after = WindowControl.tasks()[front.taskId] ?: return
        }

        WindowControl.lastOutcome = when {
            after.bounds == wanted -> WindowOutcome.Applied(front.packageName)
            // the unit clamped the rectangle (a minimum size, insets) but the
            // window did move, which is what was asked for
            after.bounds != before.bounds -> WindowOutcome.Applied(front.packageName)
            else -> WindowOutcome.Refused(front.packageName, resizable)
        }
    }

    /** Puts the rectangle back when the task in front has wandered off it. */
    private fun reapplyIfMoved(front: ForegroundApp) {
        val profile = profiles.profile(front.packageName)
        if (!profile.enabled || profiles.isProtected(front.packageName)) return
        val task = WindowControl.tasks()[front.taskId] ?: return
        if (task.bounds == bounds(profile)) return
        apply(front, profile)
    }

    /** the rectangle the profile asks for, in the pixels the unit measures in */
    private fun bounds(profile: WindowProfile): Rect {
        val screen = WindowControl.screenSize(this)
        return profile.bounds(screen.x, screen.y, WindowControl.minTaskSide(this))
    }

    // ------------------------------------------------------------ the card

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WindowMarginsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tune)
            .setContentTitle(getString(R.string.window_notification_title))
            .setContentText(getString(R.string.window_notification_text, profiles.watched().size))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL,
            getString(R.string.window_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.window_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {

        /**
         * Makes sure the watcher is running. Called from the screens, in the
         * foreground, and once after a reboot.
         *
         * Without at least one enabled profile this does nothing at all, which
         * is also how the service is stopped again: it stops itself the moment
         * the last profile is turned off.
         */
        fun ensure(context: Context) {
            val profiles = WindowProfiles(context)
            if (!profiles.enabled || profiles.watched().isEmpty()) return

            val intent = Intent(context, WindowMarginService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                // A unit (or a newer Android) that refuses a start from the
                // background simply goes without the watcher for now; opening
                // App Hub again starts it from the foreground.
                try {
                    context.startService(intent)
                } catch (t2: Throwable) {
                    // nothing else to try, and nothing else to change
                }
            }
        }

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL = "window_margins"

        /** right after a change of app, look again quickly */
        private const val BURST_MILLIS = 350L

        /** each look is a root command, so a quiet unit is polled slowly */
        private const val POLL_MILLIS = 1200L

        /** how many polls between two read-backs of the front app's rectangle */
        private const val DRIFT_POLLS = 8

        /** how long a resizable task is given to land on its new bounds */
        private const val RETRY_MILLIS = 250L

        private const val FULLSCREEN = "fullscreen"
    }
}

/**
 * The watcher has to survive a reboot without anyone opening App Hub: the whole
 * point of a profile is that an app started from the vehicle's own launcher is
 * moved too, and that launcher is the first thing on screen after a boot.
 */
class WindowBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        WindowMarginService.ensure(context)
    }
}
