package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

@OptIn(ExperimentalCoroutinesApi::class)
class DirectAudioRouteCapabilitiesTest {
    @Test
    fun `missing audio manager disables Bluetooth capture before permission probe`() {
        var permissionChecks = 0

        val available = directBluetoothCaptureAvailable(audioManagerAvailable = false) {
            permissionChecks += 1
            true
        }

        assertFalse(available)
        assertEquals(0, permissionChecks)
    }

    @Test
    fun `Bluetooth permission denial performs no routing mutation`() {
        val operations = FakeBluetoothCaptureOperations().apply { permissionGranted = false }
        val capability = SystemDirectBluetoothCaptureCapability(operations)

        assertNull(capability.acquire())

        assertEquals(1, operations.permissionChecks)
        assertTrue(operations.mutations.isEmpty())
    }

    @Test
    fun `unavailable Bluetooth profile remains nonfatal`() = runTest {
        val operations = FakeBluetoothCaptureOperations().apply {
            profileRequestAccepted = false
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)

        val lease = requireNotNull(capability.acquire())
        lease.prepare()

        assertTrue(operations.recognitionStarts.isEmpty())
        assertEquals(1, operations.startScoCalls)
        assertEquals(listOf(true), operations.scoEnabledValues)
        lease.retire()
        lease.retire()
        assertEquals(listOf(true, false), operations.scoEnabledValues)
        assertEquals(1, operations.stopScoCalls)
        capability.close()
    }

    @Test
    fun `connected profile waits for prepare before routing`() = runTest {
        val operations = FakeBluetoothCaptureOperations().apply {
            deliverHeadsetDuringRequest = true
            recognitionAccepted = false
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)

        val lease = requireNotNull(capability.acquire())

        assertTrue(operations.recognitionStarts.isEmpty())
        assertEquals(0, operations.startScoCalls)

        lease.prepare()

        assertEquals(1, operations.recognitionStarts.size)
        assertEquals(1, operations.startScoCalls)
        lease.retire()
        assertTrue(operations.recognitionStops.isEmpty())
        capability.close()
    }

    @Test
    fun `prepare routes the current replacement when two proxies connect before routing opens`() = runTest {
        val operations = FakeBluetoothCaptureOperations().apply {
            deliverHeadsetDuringRequest = true
            recognitionAccepted = true
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)
        val lease = requireNotNull(capability.acquire())
        val replacement = FakeBluetoothHeadset("replacement")
        operations.deliverHeadset(replacement)

        lease.prepare()
        lease.retire()

        assertEquals(listOf(replacement to operations.device), operations.recognitionStarts)
        assertEquals(listOf(replacement to operations.device), operations.recognitionStops)
        assertEquals(listOf(operations.headset, replacement), operations.closedHeadsets)
        capability.close()
    }

    @Test
    fun `profile wait suspends dispatcher and timeout still requests sco`() = runTest {
        val operations = FakeBluetoothCaptureOperations()
        val capability = SystemDirectBluetoothCaptureCapability(operations, profileWaitMillis = 1_000L)
        val lease = requireNotNull(capability.acquire())
        var heartbeat = false

        val preparing = async { lease.prepare() }
        launch { heartbeat = true }
        runCurrent()
        assertTrue(heartbeat)
        assertFalse(preparing.isCompleted)

        advanceTimeBy(1_000L)
        runCurrent()
        preparing.await()
        assertEquals(1, operations.startScoCalls)
        lease.retire()
        capability.close()
    }

    @Test
    fun `late profile after timeout starts recognition once without restarting sco`() = runTest {
        val operations = FakeBluetoothCaptureOperations().apply {
            dispatchImmediately = false
            recognitionAccepted = true
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations, profileWaitMillis = 1_000L)
        val lease = requireNotNull(capability.acquire())

        val preparing = async { lease.prepare() }
        advanceTimeBy(1_000L)
        runCurrent()
        preparing.await()
        assertEquals(1, operations.startScoCalls)

        operations.deliverHeadset()
        operations.drainDispatches()

        assertEquals(1, operations.recognitionStarts.size)
        assertEquals(1, operations.startScoCalls)
        lease.retire()
        capability.close()
    }

    @Test
    fun `cancelling profile wait preserves cancellation and does not route`() = runTest {
        val operations = FakeBluetoothCaptureOperations()
        val capability = SystemDirectBluetoothCaptureCapability(operations, profileWaitMillis = 1_000L)
        val lease = requireNotNull(capability.acquire())

        val preparing = launch { lease.prepare() }
        runCurrent()
        preparing.cancelAndJoin()

        assertTrue(preparing.isCancelled)
        assertEquals(0, operations.startScoCalls)
        assertTrue(operations.recognitionStarts.isEmpty())
        lease.retire()
        capability.close()
    }

    @Test
    fun `partial SCO setup retires once and stop continues after disable failure`() = runTest {
        val operations = FakeBluetoothCaptureOperations().apply {
            deliverHeadsetDuringRequest = true
            throwWhenEnablingSco = true
            throwWhenDisablingSco = true
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)

        val lease = requireNotNull(capability.acquire())
        lease.prepare()
        lease.retire()
        lease.retire()
        capability.close()

        assertEquals(1, operations.startScoCalls)
        assertEquals(listOf(true, false), operations.scoEnabledValues)
        assertEquals(1, operations.stopScoCalls)
        assertEquals(1, operations.closeCalls)
    }

    @Test
    fun `reentrant retirement rolls back SCO enable that completes late`() = runTest {
        val operations = FakeBluetoothCaptureOperations()
        val capability = SystemDirectBluetoothCaptureCapability(operations)
        lateinit var lease: DirectBluetoothCaptureLease
        operations.beforeSetScoEnabled = { enabled ->
            if (enabled) lease.retire()
        }
        lease = requireNotNull(capability.acquire())

        lease.prepare()
        val mutationsAfterPrepare = operations.mutations.toList()
        lease.retire()

        assertFalse(operations.scoEnabled)
        assertEquals(1, operations.startScoCalls)
        assertEquals(mutationsAfterPrepare, operations.mutations)
        capability.close()
    }

    @Test
    fun `accepted recognition stops exact pair and proxy closes once`() = runTest {
        val operations = FakeBluetoothCaptureOperations().apply {
            deliverHeadsetDuringRequest = true
            recognitionAccepted = true
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)

        val lease = requireNotNull(capability.acquire())
        lease.prepare()
        lease.retire()
        lease.retire()
        capability.close()
        capability.close()

        val pair = operations.headset to operations.device
        assertEquals(listOf(pair), operations.recognitionStarts)
        assertEquals(listOf(pair), operations.recognitionStops)
        assertEquals(listOf(operations.headset), operations.closedHeadsets)
        assertEquals(1, operations.closeCalls)
    }

    @Test
    fun `replacement profile closes every exact proxy after retiring original recognition`() = runTest {
        val operations = FakeBluetoothCaptureOperations().apply {
            deliverHeadsetDuringRequest = true
            recognitionAccepted = true
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)
        val lease = requireNotNull(capability.acquire())
        lease.prepare()
        val replacement = FakeBluetoothHeadset("replacement")

        operations.deliverHeadset(replacement)
        lease.retire()

        assertEquals(listOf(operations.headset to operations.device), operations.recognitionStops)
        assertEquals(listOf(operations.headset, replacement), operations.closedHeadsets)
        assertTrue(
            operations.mutations.indexOf("stop-recognition") <
                operations.mutations.indexOf("close-proxy"),
        )
        capability.close()
    }

    @Test
    fun `disconnect without reconnect still closes its exact proxy once`() = runTest {
        val operations = FakeBluetoothCaptureOperations().apply {
            deliverHeadsetDuringRequest = true
            recognitionAccepted = true
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)
        val lease = requireNotNull(capability.acquire())
        lease.prepare()

        operations.disconnectHeadset()
        lease.retire()
        lease.retire()

        assertEquals(listOf(operations.headset to operations.device), operations.recognitionStops)
        assertEquals(listOf(operations.headset), operations.closedHeadsets)
        capability.close()
    }

    @Test
    fun `disconnect and reconnect do not duplicate or forget accepted recognition`() = runTest {
        val operations = FakeBluetoothCaptureOperations().apply {
            deliverHeadsetDuringRequest = true
            recognitionAccepted = true
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)
        val lease = requireNotNull(capability.acquire())
        lease.prepare()

        operations.disconnectHeadset()
        operations.deliverHeadset()
        lease.retire()

        assertEquals(listOf(operations.headset to operations.device), operations.recognitionStarts)
        assertEquals(listOf(operations.headset to operations.device), operations.recognitionStops)
        capability.close()
    }

    @Test
    fun `callback delivered after capture retirement only closes its proxy`() = runTest {
        val operations = FakeBluetoothCaptureOperations()
        val capability = SystemDirectBluetoothCaptureCapability(operations)
        val lease = requireNotNull(capability.acquire())
        lease.retire()

        val mutationsBeforeCallback = operations.mutations.toList()
        operations.deliverHeadset()

        assertEquals(mutationsBeforeCallback + "close-proxy", operations.mutations)
        assertEquals(listOf(operations.headset), operations.closedHeadsets)
        assertTrue(operations.recognitionStarts.isEmpty())
        assertEquals(0, operations.startScoCalls)
        assertEquals(0, operations.stopScoCalls)
        capability.close()
    }

    @Test
    fun `queued recognition callback observes capture retirement before Android mutation`() = runTest {
        val operations = FakeBluetoothCaptureOperations().apply {
            dispatchImmediately = false
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)
        val lease = requireNotNull(capability.acquire())
        lease.prepare()
        operations.deliverHeadset()
        assertEquals(1, operations.pendingDispatchCount)

        lease.retire()
        val mutationsAfterRetirement = operations.mutations.toList()
        operations.drainDispatches()

        assertTrue(operations.recognitionStarts.isEmpty())
        assertEquals(mutationsAfterRetirement, operations.mutations)
        capability.close()
    }

    @Test
    fun `retirement joins admitted recognition and rolls back its late success`() = runTest {
        val recognitionEntered = CountDownLatch(1)
        val releaseRecognition = CountDownLatch(1)
        val retirementCompleted = CountDownLatch(1)
        val operations = FakeBluetoothCaptureOperations().apply {
            recognitionAccepted = true
            beforeStartRecognition = {
                recognitionEntered.countDown()
                assertTrue(releaseRecognition.await(5, TimeUnit.SECONDS))
            }
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)
        val lease = requireNotNull(capability.acquire())
        lease.prepare()

        val callback = thread(name = "direct-bluetooth-callback") {
            operations.deliverHeadset()
        }
        assertTrue(recognitionEntered.await(5, TimeUnit.SECONDS))
        val retirement = thread(name = "direct-bluetooth-retirement") {
            lease.retire()
            retirementCompleted.countDown()
        }

        try {
            assertFalse(retirementCompleted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseRecognition.countDown()
            callback.join(5_000)
            retirement.join(5_000)
        }

        assertFalse(callback.isAlive)
        assertFalse(retirement.isAlive)
        assertEquals(listOf(operations.headset to operations.device), operations.recognitionStarts)
        assertEquals(listOf(operations.headset to operations.device), operations.recognitionStops)
        assertEquals(1, operations.stopScoCalls)
        assertEquals(listOf(operations.headset), operations.closedHeadsets)
        capability.close()
    }

    @Test
    fun `reentrant SCO retirement waits for another threads admitted recognition`() = runTest {
        val recognitionEntered = CountDownLatch(1)
        val releaseRecognition = CountDownLatch(1)
        val scoEnableEntered = CountDownLatch(1)
        val proxyCloseStarted = CountDownLatch(1)
        val sawScoEnable = AtomicBoolean(false)
        val proxyClosedBeforeRecognitionRelease = AtomicBoolean(false)
        val operations = FakeBluetoothCaptureOperations().apply {
            recognitionAccepted = true
            beforeStartRecognition = {
                recognitionEntered.countDown()
                releaseRecognition.await(5, TimeUnit.SECONDS)
            }
            beforeCloseHeadsetProxy = { proxyCloseStarted.countDown() }
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)
        lateinit var lease: DirectBluetoothCaptureLease
        operations.beforeSetScoEnabled = { enabled ->
            if (enabled) {
                scoEnableEntered.countDown()
                lease.retire()
            }
        }
        lease = requireNotNull(capability.acquire())
        val preparing = async { lease.prepare() }
        runCurrent()
        val callback = thread(name = "direct-bluetooth-mixed-recognition") {
            operations.deliverHeadset()
        }
        assertTrue(recognitionEntered.await(5, TimeUnit.SECONDS))
        val observer = thread(name = "direct-bluetooth-mixed-observer") {
            sawScoEnable.set(scoEnableEntered.await(5, TimeUnit.SECONDS))
            if (sawScoEnable.get()) {
                proxyClosedBeforeRecognitionRelease.set(
                    proxyCloseStarted.await(100, TimeUnit.MILLISECONDS),
                )
            }
            releaseRecognition.countDown()
        }

        try {
            runCurrent()
            preparing.await()
        } finally {
            releaseRecognition.countDown()
            callback.join(5_000)
            observer.join(5_000)
        }

        assertTrue(sawScoEnable.get())
        assertFalse(proxyClosedBeforeRecognitionRelease.get())
        assertFalse(callback.isAlive)
        assertFalse(observer.isAlive)
        assertEquals(listOf(operations.headset to operations.device), operations.recognitionStarts)
        assertEquals(listOf(operations.headset to operations.device), operations.recognitionStops)
        assertEquals(listOf(operations.headset), operations.closedHeadsets)
        assertTrue(
            operations.mutations.indexOf("stop-recognition") <
                operations.mutations.indexOf("close-proxy"),
        )
        capability.close()
    }

    @Test
    fun `each Bluetooth acquisition owns an independent callback listener`() = runTest {
        val operations = FakeBluetoothCaptureOperations()
        val capability = SystemDirectBluetoothCaptureCapability(operations)
        val first = requireNotNull(capability.acquire())
        val second = requireNotNull(capability.acquire())
        first.prepare()
        second.prepare()

        first.retire()
        operations.deliverHeadset(listenerIndex = 0)
        operations.deliverHeadset(listenerIndex = 1)

        assertEquals(1, operations.closedHeadsets.size)
        assertEquals(2, operations.startScoCalls)
        second.retire()
        assertEquals(2, operations.stopScoCalls)
        capability.close()
    }

    @Test
    fun `late callback activates routing only while capture is active`() = runTest {
        val operations = FakeBluetoothCaptureOperations().apply {
            recognitionAccepted = true
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)
        val lease = requireNotNull(capability.acquire())
        lease.prepare()

        assertEquals(1, operations.startScoCalls)
        operations.deliverHeadset()

        assertEquals(1, operations.startScoCalls)
        assertEquals(1, operations.recognitionStarts.size)
        lease.retire()
        assertEquals(1, operations.stopScoCalls)
        capability.close()
    }

    @Test
    fun `factory close does not cancel callback owned by active capture`() = runTest {
        val operations = FakeBluetoothCaptureOperations().apply {
            recognitionAccepted = true
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)
        val lease = requireNotNull(capability.acquire())
        lease.prepare()

        capability.close()
        operations.deliverHeadset()

        assertEquals(1, operations.recognitionStarts.size)
        assertEquals(1, operations.startScoCalls)
        lease.retire()
    }

    @Test
    fun `accepted communication device clears once while rejected device never clears`() {
        val acceptedOperations = FakeCaptureDeviceOperations().apply {
            communicationDeviceAccepted = true
        }
        val accepted = SystemDirectCaptureDeviceCapability(acceptedOperations)
        val acceptedLease = requireNotNull(accepted.configure(uninitializedAudioRecord()))

        acceptedLease.retire()
        acceptedLease.retire()

        assertEquals(listOf(acceptedOperations.device), acceptedOperations.preferredDevices)
        assertEquals(listOf(acceptedOperations.device), acceptedOperations.communicationDevices)
        assertEquals(1, acceptedOperations.clearCommunicationDeviceCalls)

        val rejectedOperations = FakeCaptureDeviceOperations().apply {
            communicationDeviceAccepted = false
        }
        val rejected = SystemDirectCaptureDeviceCapability(rejectedOperations)

        assertNull(rejected.configure(uninitializedAudioRecord()))
        assertEquals(listOf(rejectedOperations.device), rejectedOperations.preferredDevices)
        assertEquals(listOf(rejectedOperations.device), rejectedOperations.communicationDevices)
        assertEquals(0, rejectedOperations.clearCommunicationDeviceCalls)
    }

    @Test
    fun `capture device permission denial skips enumeration and recorder mutation`() {
        val operations = FakeCaptureDeviceOperations().apply { permissionGranted = false }
        val capability = SystemDirectCaptureDeviceCapability(operations)

        assertNull(capability.configure(uninitializedAudioRecord()))

        assertEquals(1, operations.permissionChecks)
        assertEquals(0, operations.captureDeviceQueries)
        assertTrue(operations.preferredDevices.isEmpty())
        assertTrue(operations.communicationDevices.isEmpty())
    }
}

internal class FakeBluetoothCaptureOperations :
    DirectBluetoothCaptureOperations<FakeBluetoothHeadset, FakeBluetoothDevice> {
    val headset = FakeBluetoothHeadset("primary")
    val device = FakeBluetoothDevice("headset:00:11")
    var permissionGranted = true
    var profileRequestAccepted = true
    var deliverHeadsetDuringRequest = false
    var recognitionAccepted = false
    var throwWhenEnablingSco = false
    var throwWhenDisablingSco = false
    var dispatchImmediately = true
    var permissionProbeFailure: Throwable? = null
    var beforeStartRecognition: () -> Unit = {}
    var beforeSetScoEnabled: (Boolean) -> Unit = {}
    var beforeCloseHeadsetProxy: (FakeBluetoothHeadset) -> Unit = {}
    var permissionChecks = 0
    var startScoCalls = 0
    var scoEnabled = false
        private set
    val scoEnabledValues = mutableListOf<Boolean>()
    var stopScoCalls = 0
    val recognitionStarts = mutableListOf<Pair<FakeBluetoothHeadset, FakeBluetoothDevice>>()
    val recognitionStops = mutableListOf<Pair<FakeBluetoothHeadset, FakeBluetoothDevice>>()
    val closedHeadsets = mutableListOf<FakeBluetoothHeadset>()
    var closeCalls = 0
    val mutations = mutableListOf<String>()
    private val listeners = mutableListOf<DirectBluetoothHeadsetListener<FakeBluetoothHeadset>>()
    private val pendingDispatches = ArrayDeque<() -> Unit>()

    val pendingDispatchCount: Int
        get() = pendingDispatches.size

    override fun createCallbackDispatcher(): DirectBluetoothCallbackDispatcher =
        object : DirectBluetoothCallbackDispatcher {
            private var closed = false

            override fun dispatch(block: () -> Unit) {
                if (dispatchImmediately) {
                    if (!closed) block()
                } else {
                    pendingDispatches.addLast { if (!closed) block() }
                }
            }

            override fun close() {
                closeCalls += 1
                closed = true
            }
        }

    override fun hasConnectPermission(): Boolean {
        permissionChecks += 1
        permissionProbeFailure?.let { throw it }
        return permissionGranted
    }

    override fun requestHeadsetProxy(listener: DirectBluetoothHeadsetListener<FakeBluetoothHeadset>): Boolean {
        listeners += listener
        mutations += "request-proxy"
        if (deliverHeadsetDuringRequest) listener.onConnected(headset)
        return profileRequestAccepted
    }

    override fun closeHeadsetProxy(headset: FakeBluetoothHeadset) {
        beforeCloseHeadsetProxy(headset)
        closedHeadsets += headset
        mutations += "close-proxy"
    }

    override fun connectedDevices(headset: FakeBluetoothHeadset): List<FakeBluetoothDevice> = listOf(device)

    override fun safeLabel(device: FakeBluetoothDevice): String = device.safeLabel

    override fun startVoiceRecognition(
        headset: FakeBluetoothHeadset,
        device: FakeBluetoothDevice,
    ): Boolean {
        beforeStartRecognition()
        recognitionStarts += headset to device
        mutations += "start-recognition"
        return recognitionAccepted
    }

    override fun stopVoiceRecognition(headset: FakeBluetoothHeadset, device: FakeBluetoothDevice) {
        recognitionStops += headset to device
        mutations += "stop-recognition"
    }

    override fun startBluetoothSco() {
        startScoCalls += 1
        mutations += "start-sco"
    }

    override fun setBluetoothScoEnabled(enabled: Boolean) {
        scoEnabledValues += enabled
        mutations += "sco-enabled:$enabled"
        beforeSetScoEnabled(enabled)
        if (enabled && throwWhenEnablingSco) error("SCO enable failed")
        if (!enabled && throwWhenDisablingSco) error("SCO disable failed")
        scoEnabled = enabled
    }

    override fun stopBluetoothSco() {
        stopScoCalls += 1
        mutations += "stop-sco"
    }

    fun deliverHeadset(
        headset: FakeBluetoothHeadset = this.headset,
        listenerIndex: Int = listeners.lastIndex,
    ) {
        listeners[listenerIndex].onConnected(headset)
    }

    fun disconnectHeadset(listenerIndex: Int = listeners.lastIndex) {
        listeners[listenerIndex].onDisconnected()
    }

    fun drainDispatches() {
        while (pendingDispatches.isNotEmpty()) pendingDispatches.removeFirst().invoke()
    }
}

internal class FakeCaptureDeviceOperations : DirectCaptureDeviceOperations {
    val device = DirectAudioCaptureDevice(
        routeDevice = VoiceAudioRouteDevice(
            id = 7,
            type = VoiceAudioRouteDeviceType.BluetoothSco,
            name = "headset microphone",
        ),
        safeLabel = "7:BluetoothSco:headset microphone",
        handle = FakeCaptureDeviceHandle,
    )
    var permissionGranted = true
    var communicationDeviceAccepted = false
    var permissionProbeFailure: Throwable? = null
    var permissionChecks = 0
    var captureDeviceQueries = 0
    val preferredDevices = mutableListOf<DirectAudioCaptureDevice>()
    val communicationDevices = mutableListOf<DirectAudioCaptureDevice>()
    var clearCommunicationDeviceCalls = 0

    override fun hasConnectPermission(): Boolean {
        permissionChecks += 1
        permissionProbeFailure?.let { throw it }
        return permissionGranted
    }

    override fun captureDevices(): List<DirectAudioCaptureDevice> {
        captureDeviceQueries += 1
        return listOf(device)
    }

    override fun setPreferredDevice(recorder: AudioRecord, device: DirectAudioCaptureDevice): Boolean {
        preferredDevices += device
        return true
    }

    override fun setCommunicationDevice(device: DirectAudioCaptureDevice): Boolean {
        communicationDevices += device
        return communicationDeviceAccepted
    }

    override fun clearCommunicationDevice() {
        clearCommunicationDeviceCalls += 1
    }
}

internal data class FakeBluetoothHeadset(val id: String)

internal data class FakeBluetoothDevice(val safeLabel: String)

private data object FakeCaptureDeviceHandle : DirectAudioCaptureDeviceHandle

private fun uninitializedAudioRecord(): AudioRecord {
    val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
    unsafeField.isAccessible = true
    return (unsafeField.get(null) as Unsafe).allocateInstance(AudioRecord::class.java) as AudioRecord
}
