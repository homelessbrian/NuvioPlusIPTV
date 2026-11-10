package com.nuvio.tv.livetv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Cleanup belongs to the operation holding the repository lock, never to a cancelled waiter. */
internal suspend fun runGuideOperation(
    mutex: Mutex,
    recoverCommittedGuide: suspend () -> Unit,
    finish: (Exception?) -> Unit,
    reportFailure: (Exception) -> Unit,
    block: suspend () -> Unit
) {
    mutex.withLock {
        var failure: Exception? = null
        try {
            block()
        } catch (cancelled: CancellationException) {
            failure = cancelled
            throw cancelled
        } catch (recoverable: Exception) {
            failure = recoverable
            reportFailure(recoverable)
        } finally {
            // A cancelled mutation still has to expose the database's actual committed state.
            // Keep the owner lock until this and the busy flag are both settled.
            withContext(NonCancellable) {
                try {
                    if (failure != null) recoverCommittedGuide()
                } catch (recoveryFailure: Exception) {
                    reportFailure(recoveryFailure)
                } finally {
                    finish(failure)
                }
            }
        }
    }
}

/** A failed initial cache/check must not remove the periodic retry for the rest of this session. */
internal suspend fun maintainGuideUpdates(
    loadCache: suspend () -> Unit,
    afterCache: suspend () -> Unit,
    beforeFirstCheck: suspend () -> Unit,
    refresh: suspend () -> Unit,
    firstCheckDone: () -> Unit,
    awaitNextCheck: suspend () -> Unit,
    reportFailure: (String, Exception) -> Unit
) {
    suspend fun attempt(stage: String, work: suspend () -> Unit) {
        try {
            work()
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (recoverable: Exception) {
            reportFailure(stage, recoverable)
        }
    }

    attempt("Saved guide", loadCache)
    attempt("Update preparation", afterCache)
    beforeFirstCheck()
    attempt("Update check", refresh)
    firstCheckDone()
    while (true) {
        awaitNextCheck()
        attempt("Update check", refresh)
    }
}
