package com.nuvio.tv.remote

import android.view.KeyEvent
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RemoteControlStoreTest {
    private class Memory(var failure: Boolean = false) : RemoteControlPersistence {
        override val raw = MutableStateFlow("")
        override suspend fun compareAndSet(expected: String, value: String): Boolean {
            if (failure) throw IOException("unavailable storage")
            if (raw.value != expected) return false
            raw.value = value; return true
        }
    }
    private val left = RemoteButton(KeyEvent.KEYCODE_DPAD_LEFT)
    private fun mapped(action: RemoteAction) = RemoteConfiguration().withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, left, action)

    @Test fun discoveryDeduplicatesAliasesAndResetRetainsExtraButtonsWithUndo() = runTest {
        val memory = Memory(); val store = RemoteControlStore(memory, backgroundScope); runCurrent()
        assertEquals(RemoteMutationResult.UNCHANGED, store.addButton(RemoteButton(KeyEvent.KEYCODE_ENTER)))
        val yellow = RemoteButton(KeyEvent.KEYCODE_PROG_YELLOW)
        assertEquals(RemoteMutationResult.SAVED, store.addButton(yellow)); runCurrent()
        assertEquals(RemoteMutationResult.UNCHANGED, store.addButton(yellow))
        assertEquals(RemoteMutationResult.SAVED, store.saveBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, left, RemoteAction.LAST_CHANNEL)); runCurrent()
        assertEquals(RemoteMutationResult.SAVED, store.reset()); runCurrent()
        assertTrue(store.state.value.configuration.bindings.isEmpty())
        assertEquals(listOf(RemoteExtraButton(yellow)), store.state.value.configuration.extras)
        assertEquals(RemoteMutationResult.SAVED, store.undo()); runCurrent()
        assertEquals(RemoteAction.LAST_CHANNEL, store.state.value.configuration.bindings.single().action)
        assertFalse(store.state.value.canUndo)
    }

    @Test fun staleEditorCannotOverwriteAnotherSaveOrAnUnobservedBackupRestore() = runTest {
        val memory = Memory(); val store = RemoteControlStore(memory, backgroundScope); runCurrent()
        val editorRevision = store.state.value.revision
        assertEquals(RemoteMutationResult.SAVED, store.applyConfiguration(mapped(RemoteAction.LAST_CHANNEL), editorRevision)); runCurrent()
        assertEquals(RemoteMutationResult.STALE, store.applyConfiguration(mapped(RemoteAction.OPTIONS), editorRevision))
        val latestRevision = store.state.value.revision
        // Restore writes outside the Store. Do not run its collector before attempting this save.
        val restored = mapped(RemoteAction.SHOW_INFO)
        memory.raw.value = RemoteControlCodec.encode(restored)
        assertEquals(RemoteMutationResult.STALE, store.applyConfiguration(mapped(RemoteAction.OPTIONS), latestRevision))
        assertEquals(restored, store.state.value.configuration)
        assertEquals(restored, RemoteControlCodec.decode(memory.raw.value))
    }

    @Test fun failedSaveLeavesExistingMappingAndUndoIntact() = runTest {
        val memory = Memory(); val store = RemoteControlStore(memory, backgroundScope); runCurrent()
        store.applyConfiguration(mapped(RemoteAction.LAST_CHANNEL), store.state.value.revision); runCurrent()
        val before = store.state.value
        memory.failure = true
        assertEquals(RemoteMutationResult.FAILED, store.applyConfiguration(mapped(RemoteAction.OPTIONS), before.revision))
        assertEquals(before.configuration, store.state.value.configuration)
        assertEquals(before.canUndo, store.state.value.canUndo)
        assertEquals(RemoteStoreError.SAVE_FAILED, store.state.value.error)
        assertEquals(before.configuration, RemoteControlCodec.decode(memory.raw.value))
    }

    @Test fun corruptedStoredConfigurationFallsBackSafelyAndCanBeExplicitlyReset() = runTest {
        val memory = Memory().apply { raw.value = "{corrupt" }
        val store = RemoteControlStore(memory, backgroundScope); runCurrent()
        assertFalse(store.state.value.loading)
        assertEquals(RemoteConfiguration(), store.state.value.configuration)
        assertEquals(RemoteStoreError.LOAD_FAILED, store.state.value.error)
        assertEquals("{corrupt", memory.raw.value)
        assertEquals(RemoteMutationResult.SAVED, store.reset()); runCurrent()
        assertNull(store.state.value.error)
        assertFalse(store.state.value.canUndo)
        RemoteControlCodec.validate(memory.raw.value)
    }
}
