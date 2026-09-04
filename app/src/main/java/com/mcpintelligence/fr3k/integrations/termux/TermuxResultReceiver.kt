package com.mcpintelligence.fr3k.integrations.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the Bundle returned by Termux RunCommandService via PendingIntent. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        if (id < 0) return
        val bundle = intent.getBundleExtra(RESULT_BUNDLE)
        val stdout = bundle?.getString(RESULT_STDOUT).orEmpty()
        val stderr = bundle?.getString(RESULT_STDERR).orEmpty()
        val exit = bundle?.getInt(RESULT_EXIT_CODE, -1) ?: -1
        val error = bundle?.getString(RESULT_ERRMSG).orEmpty()
        TermuxBridge.complete(id, TermuxBridge.Result(stdout, stderr.ifBlank { error }, exit))
    }

    companion object {
        const val EXTRA_EXECUTION_ID = "fr3k.termux.execution_id"
        private const val RESULT_BUNDLE = "result"
        private const val RESULT_STDOUT = "stdout"
        private const val RESULT_STDERR = "stderr"
        private const val RESULT_EXIT_CODE = "exitCode"
        private const val RESULT_ERRMSG = "errmsg"
    }
}