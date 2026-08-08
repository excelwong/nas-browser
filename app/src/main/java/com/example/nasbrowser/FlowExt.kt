package com.example.nasbrowser

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Observe a [StateFlow] from a [LifecycleOwner], re-collecting only while the lifecycle is at
 * least STARTED. This mirrors [androidx.lifecycle.LiveData.observe] so the existing UI code can
 * stay unchanged while the ViewModel uses StateFlow under the hood.
 */
fun <T> StateFlow<T>.observe(owner: LifecycleOwner, block: (T) -> Unit) {
    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            collect { block(it) }
        }
    }
}
