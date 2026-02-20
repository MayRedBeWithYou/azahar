// Copyright 2024 Mandarine Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlin.random.Random
import org.citra.citra_emu.R
import org.citra.citra_emu.utils.Log

class WifiDirectManager(private val activity: Activity) {

    interface Listener {
        fun onSearching()
        fun onConnecting()
        fun onSettingUp()
        fun onSuccess(isHost: Boolean)
        fun onError(message: String)
    }

    var listener: Listener? = null

    private val manager: WifiP2pManager? =
        activity.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isConnecting = false
    private var isComplete = false
    private var retryCount = 0

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var connectionTimeoutRunnable: Runnable? = null

    private fun scheduleConnectionTimeout() {
        cancelConnectionTimeout()
        connectionTimeoutRunnable = Runnable {
            if (!isComplete && isConnecting) {
                Log.warning("[WifiDirectManager] Connection timed out after ${CONNECTION_TIMEOUT_MS / 1000}s")
                isConnecting = false
                channel?.let { manager?.cancelConnect(it, null) }
                listener?.onError(activity.getString(R.string.multiplayer_wifi_direct_error))
            }
        }.also { timeoutHandler.postDelayed(it, CONNECTION_TIMEOUT_MS) }
    }

    private fun cancelConnectionTimeout() {
        connectionTimeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        connectionTimeoutRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun retryDiscovery() {
        if (isComplete) return
        Log.info("[WifiDirectManager] Retrying peer discovery (attempt $retryCount/$MAX_RETRIES)")
        manager?.discoverPeers(channel ?: return, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.debug("[WifiDirectManager] Re-discovery started successfully")
            }

            override fun onFailure(reason: Int) {
                Log.error("[WifiDirectManager] Re-discovery failed (reason=$reason)")
                listener?.onError(activity.getString(R.string.multiplayer_wifi_direct_error))
            }
        })
    }

    fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(activity, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun getRequiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun startDiscovery() {
        if (manager == null) {
            Log.error("[WifiDirectManager] Wi-Fi Direct is not supported on this device")
            listener?.onError(activity.getString(R.string.multiplayer_wifi_direct_not_supported))
            return
        }

        val ch = manager.initialize(activity, activity.mainLooper, null) ?: run {
            Log.error("[WifiDirectManager] Failed to initialize WifiP2pManager channel")
            listener?.onError(activity.getString(R.string.multiplayer_wifi_direct_not_supported))
            return
        }
        channel = ch
        Log.debug("[WifiDirectManager] Channel initialized, registering receiver")

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        receiver = WifiDirectReceiver()
        ContextCompat.registerReceiver(activity, receiver!!, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        Log.info("[WifiDirectManager] Starting peer discovery")
        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.info("[WifiDirectManager] Peer discovery started successfully")
                listener?.onSearching()
            }

            override fun onFailure(reason: Int) {
                Log.error("[WifiDirectManager] Peer discovery failed (reason=$reason)")
                listener?.onError(activity.getString(R.string.multiplayer_wifi_direct_error))
            }
        })
    }

    fun stop() {
        Log.info("[WifiDirectManager] Stopping Wi-Fi Direct")
        cancelConnectionTimeout()
        isComplete = true
        isConnecting = false
        retryCount = 0
        listener = null
        try {
            receiver?.let { activity.unregisterReceiver(it) }
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered
        }
        receiver = null
        channel?.let { ch ->
            manager?.stopPeerDiscovery(ch, null)
            manager?.cancelConnect(ch, null)
            manager?.removeGroup(ch, null)
        }
        channel?.close()
        channel = null
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    private fun connectToPeer(device: WifiP2pDevice) {
        Log.info("[WifiDirectManager] Connecting to peer: ${device.deviceName} (${device.deviceAddress})")
        isConnecting = true
        listener?.onConnecting()
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Randomize intent so simultaneous connects resolve deterministically via spec tiebreaker
            // rather than relying on firmware behaviour. The device with higher intent becomes the GO.
            // Force 0 when the target is already a GO so we unambiguously join as a client.
            groupOwnerIntent = if (device.isGroupOwner) 0 else Random.nextInt(0, 16)
        }
        manager?.connect(channel ?: return, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.debug("[WifiDirectManager] Connect request accepted, awaiting group formation")
                scheduleConnectionTimeout()
            }

            override fun onFailure(reason: Int) {
                Log.error("[WifiDirectManager] Failed to connect to peer (reason=$reason)")
                if (reason == WifiP2pManager.BUSY) {
                    // The peer already initiated connection to us. Stay in connecting state and
                    // wait passively — WIFI_P2P_CONNECTION_CHANGED_ACTION will fire when the
                    // group forms. The connection timeout acts as a safety net.
                    Log.info("[WifiDirectManager] Connect BUSY — waiting for group formation from peer")
                    scheduleConnectionTimeout()
                } else {
                    cancelConnectionTimeout()
                    isConnecting = false
                    listener?.onError(activity.getString(R.string.multiplayer_wifi_direct_error))
                }
            }
        })
    }

    private fun onGroupFormed(isGroupOwner: Boolean, groupOwnerAddress: String) {
        if (isComplete) return
        isComplete = true
        cancelConnectionTimeout()
        Log.info("[WifiDirectManager] Group formed — isGroupOwner=$isGroupOwner, ownerAddress=$groupOwnerAddress")
        listener?.onSettingUp()

        val pendingListener = listener
        val port = DEFAULT_PORT
        val username = NetPlayManager.getUsername(activity)

        Thread {
            val result = if (isGroupOwner) {
                val roomName = activity.getString(R.string.multiplayer_default_room_name, username)
                Log.info("[WifiDirectManager] Creating room as host: name='$roomName', port=$port")
                NetPlayManager.netPlayCreateRoom(
                    groupOwnerAddress, port, username,
                    WIFI_DIRECT_GAME_NAME, 0L, "", roomName, MAX_PLAYERS
                )
            } else {
                Log.info("[WifiDirectManager] Joining room as client: host=$groupOwnerAddress, port=$port")
                NetPlayManager.netPlayJoinRoom(groupOwnerAddress, port, username, "")
            }

            activity.runOnUiThread {
                if (result == NetPlayManager.NetPlayStatus.NO_ERROR) {
                    Log.info("[WifiDirectManager] Room ${if (isGroupOwner) "created" else "joined"} successfully")
                    pendingListener?.onSuccess(isGroupOwner)
                } else {
                    Log.error("[WifiDirectManager] Room ${if (isGroupOwner) "creation" else "join"} failed (status=$result)")
                    pendingListener?.onError(activity.getString(R.string.multiplayer_wifi_direct_error))
                }
            }
        }.start()
    }

    private inner class WifiDirectReceiver : BroadcastReceiver() {
        @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        Log.error("[WifiDirectManager] Wi-Fi P2P disabled (state=$state)")
                        cancelConnectionTimeout()
                        isConnecting = false
                        if (!isComplete) listener?.onError(activity.getString(R.string.multiplayer_wifi_direct_error))
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (isConnecting) return
                    manager?.requestPeers(channel ?: return) { peerList ->
                        // Prefer an existing Group Owner so that a 3rd device joins the
                        // already-formed group rather than creating a separate one with a GC.
                        val peer = peerList.deviceList.firstOrNull { it.isGroupOwner }
                            ?: peerList.deviceList.firstOrNull()
                            ?: return@requestPeers
                        Log.debug("[WifiDirectManager] Peers changed: ${peerList.deviceList.size} peer(s) found, " +
                            "selected ${peer.deviceName} (isGroupOwner=${peer.isGroupOwner}, status=${peer.status})")
                        // Skip jitter when the target is already a GO — no collision race is
                        // possible. Apply jitter otherwise to desync simultaneous first-connect
                        // attempts where both devices are racing to become GO.
                        val delay = if (peer.isGroupOwner) 0L else Random.nextLong(0, CONNECT_JITTER_MS)
                        timeoutHandler.postDelayed({
                            if (!isConnecting && !isComplete) connectToPeer(peer)
                        }, delay)
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    if (isComplete) return
                    manager?.requestConnectionInfo(channel ?: return) { info ->
                        Log.debug("[WifiDirectManager] Connection changed: groupFormed=${info.groupFormed}, isGroupOwner=${info.isGroupOwner}")
                        if (info.groupFormed && !isComplete) {
                            val hostAddress = info.groupOwnerAddress?.hostAddress
                                ?: WIFI_DIRECT_HOST_IP
                            onGroupFormed(info.isGroupOwner, hostAddress)
                        } else if (!info.groupFormed && isConnecting && !isComplete) {
                            cancelConnectionTimeout()
                            isConnecting = false
                            if (retryCount < MAX_RETRIES) {
                                retryCount++
                                Log.warning("[WifiDirectManager] Connection dropped before group formed, retrying ($retryCount/$MAX_RETRIES)")
                                listener?.onSearching()
                                val delay = RETRY_DELAY_MS + Random.nextLong(0, RETRY_DELAY_MS)
                                timeoutHandler.postDelayed(::retryDiscovery, delay)
                            } else {
                                Log.error("[WifiDirectManager] Connection failed after $MAX_RETRIES retries")
                                listener?.onError(activity.getString(R.string.multiplayer_wifi_direct_error))
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_PORT = 24872
        private const val MAX_PLAYERS = 4
        private const val WIFI_DIRECT_HOST_IP = "192.168.49.1"
        private const val WIFI_DIRECT_GAME_NAME = "Wi-Fi Direct Room"
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1_000L
        private const val CONNECT_JITTER_MS = 300L
    }
}
