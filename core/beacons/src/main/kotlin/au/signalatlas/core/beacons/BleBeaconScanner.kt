package au.signalatlas.core.beacons

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock

/** Starts and stops a low-power BLE scan; [onSighting] receives every iBeacon / Eddystone advertisement heard. */
interface BeaconScanner {
    val scanning: Boolean
    fun start(onSighting: (BeaconSighting) -> Unit)
    fun stop()
}

/** BLUETOOTH_SCAN is a runtime permission on API 31+ (older versions need only location, which the app already asks for). */
fun hasBleScanPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)

/** The permissions to request when the user turns beacon use on. */
fun bleScanPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** The platform BLE scanner in `SCAN_MODE_LOW_POWER`. Does nothing without permission or Bluetooth. */
class AndroidBeaconScanner(context: Context) : BeaconScanner {
    private val context = context.applicationContext
    @Volatile private var callback: ScanCallback? = null

    override val scanning: Boolean get() = callback != null

    @SuppressLint("MissingPermission") // checked by hasBleScanPermission
    @Synchronized
    override fun start(onSighting: (BeaconSighting) -> Unit) {
        if (callback != null || !hasBleScanPermission(context)) return
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val bytes = result.scanRecord?.bytes ?: return
                BeaconParser.parse(bytes, result.rssi, SystemClock.elapsedRealtime())?.let(onSighting)
            }
        }
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), cb)
            callback = cb
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        } catch (e: IllegalStateException) {
            // Bluetooth turned off.
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (e: SecurityException) {
            // Nothing more to do.
        } catch (e: IllegalStateException) {
            // Bluetooth already off.
        }
    }
}
