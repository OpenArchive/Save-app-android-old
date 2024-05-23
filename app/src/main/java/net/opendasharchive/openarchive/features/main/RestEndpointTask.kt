package net.opendasharchive.openarchive.features.main

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import java.lang.Exception
import java.net.InetSocketAddress
import java.net.Proxy

class RestEndpointTask(private val callback: (String?) -> Unit) : Runnable {
    private val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("localhost", 9050))

    override fun run() {
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .build()

        val request = Request.Builder()
            .url("https://jsonplaceholder.typicode.com/todos/1")
            .build()

        try {
            val response = client.newCall(request).execute()

            val result = if (response.isSuccessful) {
                response.body?.string()
            } else {
                null
            }

            Handler(Looper.getMainLooper()).post {
                callback(result)
            }
        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post {
                callback(null)
            }
        }
    }
}