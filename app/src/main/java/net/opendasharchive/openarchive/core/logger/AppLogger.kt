package net.opendasharchive.openarchive.core.logger

import android.content.Context
import com.orhanobut.logger.AndroidLogAdapter
import com.orhanobut.logger.DiskLogAdapter
import com.orhanobut.logger.FormatStrategy
import com.orhanobut.logger.Logger
import com.orhanobut.logger.PrettyFormatStrategy
import net.opendasharchive.openarchive.BuildConfig
import timber.log.Timber


/**
 * A utility object for centralized logging in Android applications.
 * This object simplifies the logging process by integrating with the Timber and
 * AndroidRemoteDebugger libraries.
 *
 * Logs will only be generated if the [init] method is called. The class supports
 * different log levels and allows for conditional remote debugging.
 * The name of the class from which AppLogger was called will automatically be set as a tag
 */
object AppLogger {

    /**
     * Initializes the logger by planting a Timber DebugTree and optionally
     * initializing the AndroidRemoteDebugger.
     *
     * @param context The context used to initialize the AndroidRemoteDebugger.
     * @param initDebugger A boolean flag to determine whether AndroidRemoteDebugger
     *                     should be initialized.
     */
    fun init(context: Context, initDebugger: Boolean) {
        val formatStrategy: FormatStrategy = PrettyFormatStrategy.newBuilder()
            .showThreadInfo(false)
            .methodCount(2)
            .methodOffset(7)
            .tag("SAVE")
            .build()

        Logger.addLogAdapter(object : AndroidLogAdapter(formatStrategy) {
            override fun isLoggable(priority: Int, tag: String?): Boolean {
                return BuildConfig.DEBUG
            }
        })
        Logger.addLogAdapter(DiskLogAdapter());

        Timber.plant(object : Timber.DebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (t == null) {
                    Logger.log(priority, "-$tag", message, null)
                } else {
                    Logger.log(priority, "-$tag", message, t)
                }
            }
        })
        //if(initDebugger)
        //AndroidRemoteDebugger.init(context)
    }

    /**
     * Logs a warning message with the given format string and arguments.
     *
     * @param s The format string.
     * @param objects The arguments referenced by the format specifiers in the format string.
     */
    fun w(s: String, vararg objects: Any) {
        val tag = Thread.currentThread().stackTrace[3].fileName
        Timber.tag(tag).w(s, *objects)
        //AndroidRemoteDebugger.Log.w(tag, s)
    }

    /**
     * Logs a debug message with the given format string and arguments.
     *
     * @param s The format string.
     * @param objects The arguments referenced by the format specifiers in the format string.
     */
    fun d(s: String, vararg objects: Any) {
        val tag = Thread.currentThread().stackTrace[3].fileName
        Timber.tag(tag).d(s, *objects)
        //AndroidRemoteDebugger.Log.d(tag,s)
    }

    /**
     * Logs a debug message with a specific tag, format string, and arguments.
     *
     * @param tag The specific tag for the log message.
     * @param s The format string.
     * @param objects The arguments referenced by the format specifiers in the format string.
     */
    fun tagD(tag: String, s: String, vararg objects: Any) {
        val tagAuto = Thread.currentThread().stackTrace[3].fileName
        Timber.tag(tagAuto).d("specific TAG: $tag, $s", *objects)
        //AndroidRemoteDebugger.Log.d(tagAuto, "specific TAG: $tag, $s")
    }

    /**
     * Logs an info message with a specific tag, format string, and arguments.
     *
     * @param tag The specific tag for the log message.
     * @param s The format string.
     * @param objects The arguments referenced by the format specifiers in the format string.
     */
    fun tagI(tag: String, s: String, vararg objects: Any) {
        val tagAuto = Thread.currentThread().stackTrace[3].fileName
        Timber.tag(tagAuto).i("specific TAG: $tag, $s", *objects)
        //AndroidRemoteDebugger.Log.d(tagAuto, "specific TAG: $tag, $s")
    }

    /**
     * Logs an error message with a specific tag, format string, and arguments.
     *
     * @param tag The specific tag for the log message.
     * @param s The format string.
     * @param objects The arguments referenced by the format specifiers in the format string.
     */
    fun tagE(tag: String, s: String, vararg objects: Any) {
        val tagAuto = Thread.currentThread().stackTrace[3].fileName
        Timber.tag(tagAuto).e("specific TAG: $tag, $s", *objects)
        //AndroidRemoteDebugger.Log.d(tagAuto, "specific TAG: $tag, $s")
    }

    /**
     * Logs a verbose message with the given format string and arguments.
     *
     * @param s The format string.
     * @param objects The arguments referenced by the format specifiers in the format string.
     */
    fun v(s: String, vararg objects: Any) {
        val tag = Thread.currentThread().stackTrace[3].fileName
        Timber.tag(tag).v(s, *objects)
        //AndroidRemoteDebugger.Log.v(tag, s)
    }

    /**
     * Logs a debug message with an associated throwable, format string, and arguments.
     *
     * @param throwable The throwable to log.
     * @param s The format string.
     * @param objects The arguments referenced by the format specifiers in the format string.
     */
    fun d(throwable: Throwable, s: String, vararg objects: Any) {
        val tag = Thread.currentThread().stackTrace[3].fileName
        Timber.tag(tag).d(throwable, s, *objects)
        //AndroidRemoteDebugger.Log.d(tag, throwable.stackTrace.toString())
    }

    /**
     * Logs an error message with the given format string and arguments.
     *
     * @param s The format string.
     * @param objects The arguments referenced by the format specifiers in the format string.
     */
    fun e(s: String, vararg objects: Any) {
        val tag = Thread.currentThread().stackTrace[3].fileName
        Timber.tag(tag).e(s, *objects)
        //AndroidRemoteDebugger.Log.e(tag, s)
    }

    /**
     * Logs an error message with an associated throwable, format string, and arguments.
     *
     * @param throwable The throwable to log.
     * @param s The format string.
     * @param objects The arguments referenced by the format specifiers in the format string.
     */
    fun e(throwable: Throwable, s: String, vararg objects: Any) {
        val tag = Thread.currentThread().stackTrace[3].fileName
        Timber.tag(tag).e(throwable, s, *objects)
        //AndroidRemoteDebugger.Log.e(tag, throwable.stackTrace.toString())
    }

    /**
     * Logs an error message with an associated throwable.
     *
     * @param throwable The throwable to log.
     */
    fun e(throwable: Throwable) {
        val tag = Thread.currentThread().stackTrace[3].fileName
        Timber.tag(tag).e(throwable)
        //AndroidRemoteDebugger.Log.e(tag, throwable.stackTrace.toString())
    }

    /**
     * Logs an info message with the given format string and arguments.
     *
     * @param s The format string.
     * @param objects The arguments referenced by the format specifiers in the format string.
     */
    fun i(s: String, vararg objects: Any) {
        val tag = Thread.currentThread().stackTrace[3].fileName
        Timber.tag(tag).i(s, *objects)
        //AndroidRemoteDebugger.Log.i(tag, s)
    }

    /**
     * Logs an info message with an associated throwable, format string, and arguments.
     *
     * @param throwable The throwable to log.
     * @param s The format string.
     * @param objects The arguments referenced by the format specifiers in the format string.
     */
    fun i(throwable: Throwable, s: String, vararg objects: Any) {
        val tag = Thread.currentThread().stackTrace[3].fileName
        Timber.tag(tag).i(throwable, s, *objects)
        //AndroidRemoteDebugger.Log.i(tag, throwable.stackTrace.toString())
    }

    /**
     * Logs a warning message with an associated throwable, format string, and arguments.
     *
     * @param throwable The throwable to log.
     * @param s The format string.
     * @param objects The arguments referenced by the format specifiers in the format string.
     */
    fun w(throwable: Throwable, s: String, vararg objects: Any) {
        val tag = Thread.currentThread().stackTrace[3].fileName
        Timber.tag(tag).w(throwable, s, *objects)
        //AndroidRemoteDebugger.Log.w(tag, throwable.stackTrace.toString())
    }
}