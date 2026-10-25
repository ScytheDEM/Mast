@file:Suppress("DEPRECATION")

package au.signalatlas.core.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import au.signalatlas.core.model.Tech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * Live SIM and cell state. Callbacks (TelephonyCallback on 31+, PhoneStateListener on 29-30) trigger a refresh;
 * while started, requestCellInfoUpdate is polled every [pollIntervalMs]. Nothing runs after [stop], so callers
 * stop it when the map is not visible. Never throws: a missing permission shows up in [RadioState.permission].
 */
@SuppressLint("MissingPermission", "NewApi")
class RadioMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 2_000L,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val context = context.applicationContext
    private val baseTm = this.context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val subManager = this.context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    private val executor: Executor = this.context.mainExecutor

    private val _state = MutableStateFlow(RadioState.EMPTY.copy(updatedAtElapsedMs = clock()))
    val state: StateFlow<RadioState> = _state.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<SimInfo>>(emptyList())
    val subscriptions: StateFlow<List<SimInfo>> = _subscriptions.asStateFlow()

    private val _states = MutableStateFlow<Map<Int, RadioState>>(emptyMap())

    /**
     * The state of every active subscription, by subscription id (dual SIM). The selected subscription's entry is the
     * same value as [state]; the others have no display-override ("5G" for LTE+NR NSA) information.
     */
    val states: StateFlow<Map<Int, RadioState>> = _states.asStateFlow()

    private val _dataSubId = MutableStateFlow<Int?>(null)

    /** The default data subscription (the primary SIM in a dual-SIM phone); null when unknown. */
    val dataSubscriptionId: StateFlow<Int?> = _dataSubId.asStateFlow()

    private val lock = Any()
    /** Last cell list delivered by requestCellInfoUpdate for the subscriptions other than the selected one. */
    private val otherFresh = HashMap<Int, List<CellInfo>>()
    private var started = false
    private var selectedSubId: Int? = null
    private var displayOverride = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
    private var pollJob: Job? = null
    private var registered: Registered? = null

    private val subsListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            executor.execute { refresh() }
        }
    }

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            registerAll()
            pollJob = scope.launch {
                while (isActive) {
                    pollOnce()
                    delay(pollIntervalMs)
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            pollJob?.cancel()
            pollJob = null
            unregisterAll()
        }
    }

    /** Null selects the active data subscription. */
    fun selectSubscription(subId: Int?) {
        synchronized(lock) {
            if (selectedSubId == subId) return
            selectedSubId = subId
            displayOverride = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
            if (started) {
                unregisterAll()
                registerAll()
            }
        }
        refresh()
    }

    // --- registration ---

    private class Registered(val tm: TelephonyManager, val callback: Any)

    private fun registerAll() {
        try {
            // The Executor overload is API 30; API 29 only has the looper-bound one (the listener's constructing thread).
            if (Build.VERSION.SDK_INT >= 30) subManager.addOnSubscriptionsChangedListener(executor, subsListener)
            else subManager.addOnSubscriptionsChangedListener(subsListener)
        } catch (_: SecurityException) {
        }
        val tm = telephonyManager()
        try {
            val cb: Any = if (Build.VERSION.SDK_INT >= 31) {
                Callback31().also { tm.registerTelephonyCallback(executor, it) }
            } else {
                Listener29(executor).also { tm.listen(it, LISTENER29_EVENTS) }
            }
            registered = Registered(tm, cb)
        } catch (_: SecurityException) {
            registered = null // permission state is reported by refresh()
        }
        refresh()
    }

    private fun unregisterAll() {
        try {
            subManager.removeOnSubscriptionsChangedListener(subsListener)
        } catch (_: RuntimeException) {
        }
        val r = registered ?: return
        registered = null
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                r.tm.unregisterTelephonyCallback(r.callback as TelephonyCallback)
            } else {
                r.tm.listen(r.callback as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (_: RuntimeException) {
        }
    }

    @SuppressLint("NewApi")
    private inner class Callback31 : TelephonyCallback(),
        TelephonyCallback.CellInfoListener,
        TelephonyCallback.DisplayInfoListener,
        TelephonyCallback.ServiceStateListener {
        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) = publish(cellInfo)
        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            displayOverride = telephonyDisplayInfo.overrideNetworkType
            refresh()
        }
        override fun onServiceStateChanged(serviceState: ServiceState) = refresh()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private inner class Listener29(executor: Executor) : PhoneStateListener(executor) {
        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) = publish(cellInfo.orEmpty())
        override fun onServiceStateChanged(serviceState: ServiceState?) = refresh()

        // TelephonyDisplayInfo exists from API 30; on 29 the override stays NONE.
        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            displayOverride = telephonyDisplayInfo.overrideNetworkType
            refresh()
        }
    }

    // --- reading ---

    /** Re-reads from the platform's cached cell list. */
    fun refresh() {
        publish(null)
    }

    private fun pollOnce() {
        val permission = permission()
        if (permission != Permission.GRANTED || airplane()) {
            publish(null)
            return
        }
        try {
            telephonyManager().requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) = publish(cellInfo)
                override fun onError(errorCode: Int, detail: Throwable?) = publish(null)
            })
        } catch (_: SecurityException) {
            publish(null)
        } catch (_: RuntimeException) {
            publish(null)
        }
        // The other SIM(s) of a dual-SIM phone are polled too, so every row of the cellular details stays live.
        val selected = _state.value.sim?.subId ?: resolveSubId()
        for (sub in _subscriptions.value) {
            if (sub.subId == selected) continue
            try {
                forSub(sub.subId).requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        synchronized(lock) { otherFresh[sub.subId] = cellInfo.toList() }
                        refresh()
                    }
                    override fun onError(errorCode: Int, detail: Throwable?) = Unit
                })
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun telephonyManager(): TelephonyManager {
        val id = resolveSubId() ?: return baseTm
        return forSub(id)
    }

    private fun forSub(subId: Int): TelephonyManager = baseTm.createForSubscriptionId(subId) ?: baseTm

    private fun resolveSubId(): Int? {
        selectedSubId?.let { return it }
        val data = SubscriptionManager.getDefaultDataSubscriptionId()
        return data.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
    }

    private fun permission(): Permission = when {
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ->
            Permission.MISSING_PHONE
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
            Permission.MISSING_LOCATION
        else -> Permission.GRANTED
    }

    private fun airplane(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    } catch (_: RuntimeException) {
        false
    }

    private fun readSubscriptions(): List<SimInfo> {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        return try {
            subManager.activeSubscriptionInfoList.orEmpty().map { info ->
                val mccMnc = if (info.mccString != null && info.mncString != null) info.mccString + info.mncString else null
                SimInfo(
                    subId = info.subscriptionId,
                    network = SimMapping.networkFor(mccMnc),
                    carrierName = info.carrierName?.toString(),
                    mccMnc = mccMnc,
                    roaming = try {
                        forSub(info.subscriptionId).isNetworkRoaming
                    } catch (_: RuntimeException) {
                        false
                    },
                )
            }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /** [fresh] is a just-delivered cell list; null means read the platform cache. */
    private fun publish(fresh: List<CellInfo>?) {
        val permission = permission()
        val airplane = airplane()
        val subs = if (permission == Permission.MISSING_PHONE) emptyList() else readSubscriptions()
        _subscriptions.value = subs
        val sim = (resolveSubId()?.let { id -> subs.firstOrNull { it.subId == id } }) ?: subs.firstOrNull()

        var cells: List<ObservedCell> = emptyList()
        if (permission == Permission.GRANTED && !airplane) {
            val raw = try {
                fresh ?: telephonyManager().allCellInfo
            } catch (_: SecurityException) {
                null
            } catch (_: RuntimeException) {
                null
            }
            cells = raw.orEmpty().mapNotNull { CellMapper.map(it) }
        }
        val main = buildState(permission, airplane, sim, cells, displayOverride)
        _state.value = main

        _dataSubId.value = SubscriptionManager.getDefaultDataSubscriptionId().takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        val all = LinkedHashMap<Int, RadioState>()
        for (info in subs) {
            all[info.subId] = if (info.subId == sim?.subId) {
                main
            } else {
                val others = if (permission == Permission.GRANTED && !airplane) otherCells(info.subId) else emptyList()
                buildState(permission, airplane, info, others, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE)
            }
        }
        _states.value = all
    }

    private fun otherCells(subId: Int): List<ObservedCell> {
        val raw = synchronized(lock) { otherFresh[subId] } ?: try {
            forSub(subId).allCellInfo
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        return raw.orEmpty().mapNotNull { CellMapper.map(it) }
    }

    private fun buildState(permission: Permission, airplane: Boolean, sim: SimInfo?, cells: List<ObservedCell>, override: Int): RadioState {
        val split = CellMapper.split(cells)
        val ca = readCa(sim, permission, airplane)
        return RadioState(
            permission = permission,
            sim = sim,
            serving = split.serving,
            neighbours = split.neighbours,
            nrSecondaries = split.nrSecondaries,
            displayFiveG = !airplane && CellMapper.displayFiveG(split.serving, override),
            airplane = airplane,
            updatedAtElapsedMs = clock(),
            ca = ca,
            displayLabel = if (airplane) null else DisplayLabel.of(split.serving?.tech, override, ca),
        )
    }

    /** `ServiceState.getCellBandwidths()` of [subId]; null on a missing permission or an empty array. */
    private fun readCa(sim: SimInfo?, permission: Permission, airplane: Boolean): CarrierAggregation? {
        if (permission != Permission.GRANTED || airplane) return null
        return try {
            val tm = sim?.let { forSub(it.subId) } ?: telephonyManager()
            CarrierAggregation.from(tm.serviceState?.cellBandwidths)
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}

private val LISTENER29_EVENTS = PhoneStateListener.LISTEN_CELL_INFO or PhoneStateListener.LISTEN_SERVICE_STATE or
    (if (Build.VERSION.SDK_INT >= 30) PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED else 0)
