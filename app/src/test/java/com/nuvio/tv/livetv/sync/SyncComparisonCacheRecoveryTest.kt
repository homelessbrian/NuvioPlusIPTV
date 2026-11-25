package com.nuvio.tv.livetv.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.domain.model.ServerCapabilities
import com.nuvio.tv.domain.model.ServerConfiguration
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.vpn.android.VpnBackup
import io.github.jan.supabase.auth.Auth
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.spyk
import io.mockk.unmockkStatic
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Local comparison files are optional; the real sync owners must survive their failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncComparisonCacheRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var prefs: LiveTvPreferences
    private lateinit var local: JSONObject
    private val applied = mutableListOf<JSONObject>()
    private val owners = mutableListOf<Any>()
    private val clients = mutableListOf<OkHttpClient>()
    private var cryptoMocked = false
    private val removed = setOf("removed", "removed-guide", "readded", "readded-guide")

    @Before fun setUp() {
        // Host tests do not package the app's string resources. Keep status reporting real,
        // with stable labels instead of depending on an Android resource APK.
        val application = RuntimeEnvironment.getApplication()
        val statusResources = object : Resources(application.assets, application.resources.displayMetrics, application.resources.configuration) {
            override fun getText(id: Int): CharSequence = "fixture-status-$id"
        }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = temporary.root
            override fun getResources(): Resources = statusResources
            override fun createConfigurationContext(configuration: Configuration): Context = this
        }
        for (name in listOf("livetv_drive_sync", "livetv_cloud_sync")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        local = setup(listOf("local", "shared", "readded"), listOf("local-guide", "shared-guide", "readded-guide"))
        prefs = mockk()
        coEvery { prefs.exportForSync() } answers { copy(local) }
        coEvery { prefs.removedSourceIds() } returns removed
        coEvery { prefs.forgetRemovedSources(any()) } just Runs
        every { prefs.syncHasSources(any()) } answers {
            firstArg<JSONObject?>()?.let { data -> ids(data, "playlists").isNotEmpty() || ids(data, "epg_sources").isNotEmpty() } == true
        }
    }

    @After fun tearDown() {
        owners.forEach { (field(it, "scope") as CoroutineScope).cancel() }
        clients.forEach { it.dispatcher.executorService.shutdownNow(); it.connectionPool.evictAll() }
        resetCrypto()
    }

    @Test fun `missing and valid legacy caches retain existing account boundaries`() {
        val drive = newDrive()
        val cloud = newCloud(drive)
        cloudStore().edit().putString("base_user", "account-a").commit()
        assertNull(read(drive))
        assertNull(read(cloud, "account-a"))
        val legacy = setup(listOf("saved"), listOf("saved-guide"))
            .put("usenet_sources_v1_p2", entry("legacy-synthetic-configuration"))
        cache(drive).writeText(legacy.toString())
        cache(cloud).writeText(legacy.toString())
        assertEquals(legacy.toString(), read(drive).toString())
        assertEquals(legacy.toString(), read(cloud, "account-a").toString())
        assertNull(read(cloud, "account-b"))
    }

    @Test fun `corrupt JSON and damaged ciphertext become absent caches in both owners`() {
        val drive = newDrive()
        val cloud = newCloud(drive)
        cloudStore().edit().putString("base_user", "account-a").commit()
        for (damaged in listOf("not JSON", """{"usenet_sources_v1_p2":{"localEncrypted":"damaged","iv":"AAAA"}}""")) {
            cache(drive).writeText(damaged)
            cache(cloud).writeText(damaged)
            assertNull(read(drive))
            assertNull(read(cloud, "account-a"))
        }
    }

    @Test fun `an unavailable keystore key does not escape either cache reader`() {
        val drive = newDrive()
        val cloud = newCloud(drive)
        cloudStore().edit().putString("base_user", "account-a").commit()
        mockkStatic(VpnBackup::class)
        cryptoMocked = true
        every { VpnBackup.openCache(any<String>()) } throws IOException("Synthetic cache key unavailable")
        val sealed = """{"usenet_sources_v1_p2":{"localEncrypted":"opaque","iv":"opaque"}}"""
        cache(drive).writeText(sealed)
        cache(cloud).writeText(sealed)
        assertNull(read(drive))
        assertNull(read(cloud, "account-a"))
    }

    @Test fun `first-time recovery honors removals even when a source family is absent`() {
        val drive = newDrive()
        val remote = setup(listOf("removed", "remote"), listOf("removed-guide", "remote-guide"))
            .put("usenet_sources_v1_p2", entry("retained-configuration"))
        for (preferLocal in listOf(false, true)) {
            for (emptyLocal in listOf(JSONObject(), setup(emptyList(), emptyList()))) {
                val merged = drive.merge(null, emptyLocal, remote, preferLocal, removed)
                assertEquals(setOf("remote"), ids(merged, "playlists"))
                assertEquals(setOf("remote-guide"), ids(merged, "epg_sources"))
                assertEquals("retained-configuration", merged.getJSONObject("usenet_sources_v1_p2").getString("v"))
            }
            val readded = setup(listOf("readded"), listOf("readded-guide"))
            val merged = drive.merge(null, readded, JSONObject(), preferLocal, removed)
            assertEquals(setOf("readded"), ids(merged, "playlists"))
            assertEquals(setOf("readded-guide"), ids(merged, "epg_sources"))
        }
    }

    @Test fun `filesystem read and write failures leave sync bookkeeping usable`() {
        val drive = newDrive()
        val cloud = newCloud(drive)
        cloudStore().edit().putString("base_user", "account-a").commit()
        for (owner in listOf(drive, cloud)) {
            cache(owner).mkdir()
            File(cache(owner), "occupied").writeText("keep") // Also refuses best-effort deletion.
        }
        assertNull(read(drive))
        assertNull(read(cloud, "account-a"))
        invoke(drive, "writeBase", copy(local))
        suspendInvoke<Unit>(cloud, "saved", "account-a", copy(local), removed)
        assertNull(read(drive))
        assertNull(read(cloud, "account-a"))
        assertFalse(driveStore().getBoolean("base_valid", true))
        assertFalse(cloudStore().contains("base_user"))
        assertSuccessfulCloudBookkeeping()
        coVerify(exactly = 1) { prefs.forgetRemovedSources(removed) }
    }

    @Test fun `failed Drive sealing invalidates undeletable old cache across reopen and can recover`() {
        val drive = newDrive()
        val old = setup(listOf("old"), listOf("old-guide"))
        cache(drive).writeText(old.toString())
        every { drive["baseFile"]() } returns UndeletableFile(cache(drive))
        failSealing()
        invoke(drive, "writeBase", copy(local))
        assertEquals(old.toString(), cache(drive).readText())
        assertNull(read(drive))
        val reopened = newDrive()
        assertNull(read(reopened))
        resetCrypto()
        invoke(reopened, "writeBase", copy(local))
        assertEquals(local.toString(), read(reopened).toString())
        assertEquals(local.toString(), read(newDrive()).toString())
    }

    @Test fun `failed Cloud sealing never relabels an undeletable cache from another account`() {
        val drive = newDrive()
        val cloud = newCloud(drive)
        val old = setup(listOf("previous-account"), emptyList())
        cache(cloud).writeText(old.toString())
        cloudStore().edit().putString("base_user", "previous-account").commit()
        every { cloud["baseFile"]() } returns UndeletableFile(cache(cloud))
        failSealing()
        suspendInvoke<Unit>(cloud, "saved", "current-account", copy(local), removed)
        assertEquals(old.toString(), cache(cloud).readText())
        assertFalse(cloudStore().contains("base_user"))
        val reopened = newCloud(drive)
        assertNull(read(reopened, "previous-account"))
        assertNull(read(reopened, "current-account"))
        assertSuccessfulCloudBookkeeping()
        coVerify(exactly = 1) { prefs.forgetRemovedSources(removed) }
        resetCrypto()
        suspendInvoke<Unit>(reopened, "saved", "current-account", copy(local), emptySet<String>())
        assertEquals(local.toString(), read(newCloud(drive), "current-account").toString())
        assertNull(read(newCloud(drive), "previous-account"))
    }

    @Test fun `automatic Drive push merges and completes despite unreadable and unwritable cache`() {
        val drive = newDrive()
        prepareDrive(drive)
        cache(drive).writeText("damaged comparison cache")
        failSealing()
        var uploaded: JSONObject? = null
        transport(drive) { request ->
            when (request.method) {
                "GET" -> envelope(remote())
                "PATCH" -> { uploaded = body(request).getJSONObject("data"); JSONObject() }
                else -> error("Unexpected in-memory request: ${request.method}")
            }
        }
        suspendInvoke<Unit>(drive, "push", false)
        assertSourcesMerged(checkNotNull(uploaded))
        assertSourcesMerged(applied.single())
        assertTrue(driveStore().contains("last_sync"))
        assertEquals(sha(checkNotNull(uploaded).toString()), driveStore().getString("last_hash", null))
        assertFalse(drive.state.value.busy)
        assertNull(drive.state.value.message)
        assertNull(read(drive))
        coVerify(exactly = 1) { prefs.forgetRemovedSources(removed) }
    }

    @Test fun `automatic Drive pull applies the first-time union despite cache failures`() {
        val drive = newDrive()
        prepareDrive(drive)
        val remote = remote()
        cache(drive).writeText("damaged comparison cache")
        failSealing()
        transport(drive) { request ->
            check(request.method == "GET")
            envelope(remote)
        }
        assertTrue(suspendInvoke(drive, "pull", false, false))
        (field(drive, "scope") as CoroutineScope).cancel() // Cancel its intentional delayed push.
        assertSourcesMerged(applied.single())
        assertFalse(drive.state.value.busy)
        assertTrue(driveStore().contains("last_sync"))
        assertEquals(sha(remote.toString()), driveStore().getString("last_hash", null))
        assertNull(read(drive))
    }

    @Test fun `automatic Cloud reconciliation completes merge and acknowledgments after cache failures`() {
        val drive = newDrive()
        val cloud = newCloud(drive)
        cloudStore().edit().putString("base_user", "account-a").commit()
        cache(cloud).writeText("damaged comparison cache")
        failSealing()
        var uploaded: JSONObject? = null
        transport(cloud) { request ->
            when (request.method) {
                "GET" -> JSONObject().put("revision", 7).put("data", remote())
                "PUT" -> { uploaded = body(request).getJSONObject("data"); JSONObject().put("revision", 8) }
                else -> error("Unexpected in-memory request: ${request.method}")
            }
        }
        suspendInvoke<Unit>(cloud, "reconcileAndReport", "account-a", null)
        assertSourcesMerged(checkNotNull(uploaded))
        assertSourcesMerged(applied.single())
        assertSuccessfulCloudBookkeeping()
        assertFalse(cloud.state.value.busy)
        assertEquals(cloudStore().getLong("last_sync", -1), cloud.state.value.lastSyncMs)
        assertFalse(cloudStore().contains("base_user"))
        assertNull(read(cloud, "account-a"))
        coVerify(exactly = 1) { prefs.forgetRemovedSources(removed) }
    }

    private fun newDrive(): LiveTvDriveSync {
        val owner = spyk(LiveTvDriveSync(context, prefs, mockk<LiveTvRepository>()), recordPrivateCalls = true)
        owners += owner
        coEvery { owner.applyLocally(any(), any()) } answers {
            copy(firstArg<JSONObject>()).also { applied += it; local = it }
            Unit
        }
        return owner
    }

    private fun newCloud(drive: LiveTvDriveSync): LiveTvCloudSync {
        val auth = mockk<Auth>()
        every { auth.currentAccessTokenOrNull() } returns "synthetic-access-token"
        val configuration = ServerConfiguration("https://api.nuvio.tv", "synthetic-key", ServerCapabilities(true, true), false)
        return spyk(LiveTvCloudSync(context, prefs, drive, mockk<AuthManager>(), auth, configuration), recordPrivateCalls = true)
            .also { owners += it }
    }

    private fun prepareDrive(drive: LiveTvDriveSync) {
        driveStore().edit().putString("refresh_token", "synthetic-refresh-token")
            .putString("file_id", "synthetic-file").putString("device_id", "local-device").commit()
        setField(drive, "accessToken", "synthetic-access-token")
        setField(drive, "accessTokenExpiresAt", System.currentTimeMillis() + 3_600_000L)
    }

    /** Application interceptors return every response directly; no socket/DNS/provider call occurs. */
    private fun transport(owner: Any, respond: (Request) -> JSONObject) {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(respond(chain.request()).toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        clients += client
        setField(owner, "http", client)
    }

    private fun assertSuccessfulCloudBookkeeping() {
        assertTrue(cloudStore().contains("last_sync"))
        assertEquals(sha(local.toString()), cloudStore().getString("local_hash", null))
        assertFalse(cloudStore().getBoolean("sync_part_fresh_BEHAVIOR", true))
    }

    private fun assertSourcesMerged(data: JSONObject) {
        assertEquals(setOf("local", "shared", "readded", "remote"), ids(data, "playlists"))
        assertEquals(setOf("local-guide", "shared-guide", "readded-guide", "remote-guide"), ids(data, "epg_sources"))
    }

    private fun remote() = setup(listOf("remote", "shared", "removed"), listOf("remote-guide", "shared-guide", "removed-guide"))
    private fun envelope(data: JSONObject) = JSONObject().put("data", data).put("deviceId", "other-device").put("savedAt", 42)
    private fun setup(playlists: List<String>, guides: List<String>) = JSONObject()
        .put("playlists", entry(JSONArray(playlists.map { JSONObject().put("id", it) }).toString()))
        .put("epg_sources", entry(JSONArray(guides.map { JSONObject().put("id", it) }).toString()))
    private fun entry(value: String) = JSONObject().put("t", "str").put("v", value)
    private fun copy(data: JSONObject) = JSONObject(data.toString())
    private fun ids(data: JSONObject, key: String): Set<String> = data.optJSONObject(key)?.optString("v")
        ?.takeIf { it.isNotBlank() }?.let { raw -> JSONArray(raw).let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet() } }.orEmpty()
    private fun cache(owner: Any) = File(context.filesDir, if (owner is LiveTvDriveSync) "livetv_drive_base.json" else "livetv_cloud_base.json")
    private fun driveStore() = context.getSharedPreferences("livetv_drive_sync", Context.MODE_PRIVATE)
    private fun cloudStore() = context.getSharedPreferences("livetv_cloud_sync", Context.MODE_PRIVATE)
    private fun read(owner: Any, vararg arguments: Any?) = invoke(owner, "readBase", *arguments) as JSONObject?
    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun failSealing() {
        mockkStatic(VpnBackup::class)
        cryptoMocked = true
        every { VpnBackup.sealCache(any<String>()) } throws IOException("Synthetic cache key unavailable")
    }
    private fun resetCrypto() { if (cryptoMocked) { unmockkStatic(VpnBackup::class); cryptoMocked = false } }
    private class UndeletableFile(file: File) : File(file.path) { override fun delete() = false }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun setField(owner: Any, name: String, value: Any) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    private fun method(owner: Any, name: String, count: Int) = owner.javaClass.declaredMethods.single { it.name == name && it.parameterCount == count }.apply { isAccessible = true }
    private fun invoke(owner: Any, name: String, vararg arguments: Any?): Any? = try {
        method(owner, name, arguments.size).invoke(owner, *arguments)
    } catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
    private fun <T> suspendInvoke(owner: Any, name: String, vararg arguments: Any?): T = runBlocking {
        suspendCoroutineUninterceptedOrReturn<T> { continuation ->
            try { method(owner, name, arguments.size + 1).invoke(owner, *arguments, continuation) }
            catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
        }
    }
}
