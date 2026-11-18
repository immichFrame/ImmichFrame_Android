package com.immichframe.immichframe

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/**
 * Advertises the RPC server on the local network over mDNS (DNS-SD), so a frame can be
 * found by name rather than by an IP address that may change.
 *
 * The service is published as `<name>._immichframe._tcp`. If two frames share a name the
 * system appends a number to the second, so frames are best given distinct names.
 */
class RpcDiscovery(context: Context) {
    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var listener: NsdManager.RegistrationListener? = null
    private var registeredName: String? = null

    /**
     * Publishes the service, or republishes it if the name has changed.
     * Does nothing when the same name is already published or the port is not valid.
     */
    fun register(name: String, port: Int, version: String?) {
        val serviceName = name.trim().ifEmpty { "ImmichFrame ${Build.MODEL}" }
        if (port <= 0 || serviceName == registeredName) return
        unregister()

        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
            this.port = port
            if (version != null) setAttribute("version", version)
        }
        val newListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Advertising as ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Unable to advertise (error $errorCode)")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            }
        }
        try {
            nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, newListener)
            listener = newListener
            registeredName = serviceName
        } catch (e: Exception) {
            // Discovery is a convenience; the frame must carry on without it
            Log.w(TAG, "Unable to advertise: ${e.message}")
        }
    }

    fun unregister() {
        val current = listener ?: return
        try {
            nsdManager?.unregisterService(current)
        } catch (e: IllegalArgumentException) {
            // The registration never completed, so there is nothing to withdraw
        }
        listener = null
        registeredName = null
    }

    companion object {
        const val SERVICE_TYPE = "_immichframe._tcp"
        private const val TAG = "RpcDiscovery"
    }
}
